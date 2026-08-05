# IRSA — 서비스 어카운트별 IAM 역할.
#
# 노드 역할에 권한을 몰아주면 그 노드의 모든 Pod가 같은 권한을 얻는다.
# IRSA는 "이 네임스페이스의 이 서비스 어카운트"로 한정한다.

locals {
  oidc_provider_arn = aws_iam_openid_connect_provider.this.arn
  # sub 조건에 쓸 issuer 호스트(https:// 제거)
  oidc_issuer_host = replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")

  lbc_policy_path      = "${path.module}/policies/aws-load-balancer-controller.json"
  lbc_policy_available = fileexists(local.lbc_policy_path)
}

# 서비스 어카운트를 정확히 지정하는 신뢰 정책을 만든다.
# StringEquals로 sub를 고정하지 않으면 클러스터 내 임의의 SA가 이 역할을 맡을 수 있다.
data "aws_iam_policy_document" "irsa_assume" {
  for_each = {
    ebs_csi = "system:serviceaccount:kube-system:ebs-csi-controller-sa"
    lbc     = "system:serviceaccount:kube-system:aws-load-balancer-controller"
    ca      = "system:serviceaccount:kube-system:cluster-autoscaler"
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

  lifecycle {
    # 파일이 없으면 plan 단계에서 이유와 함께 멈춘다.
    # (역할만 만들어지고 권한이 비는 상태로 apply되면, Ingress가 만들어지지 않는
    #  원인을 런타임에 추적해야 한다 — 그 전에 끊는 편이 낫다.)
    precondition {
      condition     = local.lbc_policy_available
      error_message = "AWS Load Balancer Controller IAM 정책 파일이 없다. modules/eks/policies/README.md의 절차로 내려받아라."
    }
  }
}

resource "aws_iam_role_policy" "load_balancer_controller" {
  # fmt/validate는 자격증명 없이 돌아야 하므로, 파일 부재 시 평가 자체를 건너뛴다.
  count = local.lbc_policy_available ? 1 : 0

  name   = "aws-load-balancer-controller"
  role   = aws_iam_role.load_balancer_controller.id
  policy = file(local.lbc_policy_path)
}
