# IRSA — 서비스 어카운트별 IAM 역할.
#
# 노드 역할에 권한을 몰아주면 그 노드의 모든 Pod가 같은 권한을 얻는다.
# IRSA는 "이 네임스페이스의 이 서비스 어카운트"로 한정한다.

# 모듈에 region 변수가 없어 provider 설정에서 가져온다(계정도 동일).
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  oidc_provider_arn = aws_iam_openid_connect_provider.this.arn
  # sub 조건에 쓸 issuer 호스트(https:// 제거)
  oidc_issuer_host = replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")

  lbc_policy_path = "${path.module}/policies/aws-load-balancer-controller.json"
}

# 서비스 어카운트를 정확히 지정하는 신뢰 정책을 만든다.
# StringEquals로 sub를 고정하지 않으면 클러스터 내 임의의 SA가 이 역할을 맡을 수 있다.
data "aws_iam_policy_document" "irsa_assume" {
  for_each = {
    ebs_csi = "system:serviceaccount:kube-system:ebs-csi-controller-sa"
    lbc     = "system:serviceaccount:kube-system:aws-load-balancer-controller"
    ca      = "system:serviceaccount:kube-system:cluster-autoscaler"
    eso     = "system:serviceaccount:external-secrets:external-secrets"
  }

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_issuer_host}:sub"
      values   = [each.value]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_issuer_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

# ---------------------------------------------------------------------------
# EBS CSI Driver
# ---------------------------------------------------------------------------
resource "aws_iam_role" "ebs_csi" {
  name               = "${var.cluster_name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.irsa_assume["ebs_csi"].json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# ---------------------------------------------------------------------------
# Cluster Autoscaler
# ---------------------------------------------------------------------------
# HPA는 Pod 수만 늘린다. 노드 용량을 늘리는 건 CA다 — 둘 다 없으면
# "HPA를 적용했다"와 "실제로 확장됐다"가 달라진다(ADR-012 §4).
data "aws_iam_policy_document" "cluster_autoscaler" {
  statement {
    sid       = "Discovery"
    effect    = "Allow"
    resources = ["*"]
    actions = [
      "autoscaling:DescribeAutoScalingGroups",
      "autoscaling:DescribeAutoScalingInstances",
      "autoscaling:DescribeLaunchConfigurations",
      "autoscaling:DescribeScalingActivities",
      "autoscaling:DescribeTags",
      "ec2:DescribeImages",
      "ec2:DescribeInstanceTypes",
      "ec2:DescribeLaunchTemplateVersions",
      "ec2:GetInstanceTypesFromInstanceRequirements",
      "eks:DescribeNodegroup",
    ]
  }

  statement {
    sid       = "Scale"
    effect    = "Allow"
    resources = ["*"]
    actions = [
      "autoscaling:SetDesiredCapacity",
      "autoscaling:TerminateInstanceInAutoScalingGroup",
    ]

    # 이 클러스터의 노드그룹 ASG로만 제한한다 — 계정 내 다른 ASG를 건드리지 못하게.
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/k8s.io/cluster-autoscaler/${var.cluster_name}"
      values   = ["owned"]
    }
  }
}

resource "aws_iam_role" "cluster_autoscaler" {
  name               = "${var.cluster_name}-cluster-autoscaler"
  assume_role_policy = data.aws_iam_policy_document.irsa_assume["ca"].json
  tags               = var.tags
}

resource "aws_iam_role_policy" "cluster_autoscaler" {
  name   = "cluster-autoscaler"
  role   = aws_iam_role.cluster_autoscaler.id
  policy = data.aws_iam_policy_document.cluster_autoscaler.json
}

# ---------------------------------------------------------------------------
# AWS Load Balancer Controller
# ---------------------------------------------------------------------------
# ⚠️ 이 컨트롤러의 IAM 정책은 AWS 관리형 정책이 없고, 공식 저장소가 배포하는 JSON을
#    그대로 쓰는 것이 표준 절차다. 손으로 옮겨 적으면 누락·오타로 Ingress 생성이
#    조용히 실패하므로, 파일을 내려받아 두고 여기서 읽는다.
#    받는 법: modules/eks/policies/README.md
resource "aws_iam_role" "load_balancer_controller" {
  name               = "${var.cluster_name}-lbc"
  assume_role_policy = data.aws_iam_policy_document.irsa_assume["lbc"].json
  tags               = var.tags
}

resource "aws_iam_role_policy" "load_balancer_controller" {
  name   = "aws-load-balancer-controller"
  role   = aws_iam_role.load_balancer_controller.id
  policy = file(local.lbc_policy_path)
}

# ---------------------------------------------------------------------------
# External Secrets Operator
# ---------------------------------------------------------------------------
# 왜 필요한가: flowticket-api-secrets(키 11개)를 지금까지 **손으로** 만들었다. 클러스터를
# 재생성할 때마다 사람이 값을 다시 넣어야 했고, 2026-08-11 재기동에서만 네 번 실패했다
# (PowerShell 인코딩, 없는 길이 제약 오탐, RDS 비밀번호의 '>'가 플레이스홀더 검사에 걸림).
# 매니페스트만으로 복구되지 않는 유일한 구멍이었다.
#
# 저장소로 SSM Parameter Store를 쓴다(Secrets Manager 아님):
#   - Standard 파라미터 + SecureString은 **저장 비용이 없다**. Secrets Manager는 시크릿당 과금
#   - 로테이션이 필요 없는 값들이라 Secrets Manager의 이점이 없다
#   - DB 자격증명만은 예외로 이미 Secrets Manager에 있다(RDS가 마스터 암호를 관리) — 그쪽은
#     ExternalSecret이 별도 provider로 읽는다
#
# 권한은 경로로 좁힌다. /flowticket/* 밖은 읽지 못한다.
data "aws_iam_policy_document" "external_secrets" {
  statement {
    sid    = "ReadFlowticketParameters"
    effect = "Allow"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter/flowticket/*",
    ]
  }

  # RDS 마스터 자격증명은 Secrets Manager에 있다(rds!db-... 형식).
  statement {
    sid       = "ReadRdsManagedSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["arn:${data.aws_partition.current.partition}:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:rds!db-*"]
  }

  # SecureString 복호화. 기본 키(alias/aws/ssm)로 암호화하므로 KMS 권한이 필요하다.
  statement {
    sid       = "DecryptSecureString"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values = [
        "ssm.${data.aws_region.current.name}.amazonaws.com",
        "secretsmanager.${data.aws_region.current.name}.amazonaws.com",
      ]
    }
  }
}

resource "aws_iam_role" "external_secrets" {
  name               = "${var.cluster_name}-external-secrets"
  assume_role_policy = data.aws_iam_policy_document.irsa_assume["eso"].json
  tags               = var.tags
}

resource "aws_iam_role_policy" "external_secrets" {
  name   = "external-secrets"
  role   = aws_iam_role.external_secrets.id
  policy = data.aws_iam_policy_document.external_secrets.json
}
