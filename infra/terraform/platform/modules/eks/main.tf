# EKS — 클러스터 + AZ별 노드그룹 3개 + 애드온.
#
# 노드그룹을 AZ마다 분리하는 이유는 EBS다(ADR-012 §3).
# 서브넷 3개를 가진 노드그룹 하나로 두면, 노드가 죽었을 때 ASG가 다른 AZ에 대체 노드를
# 띄울 수 있고 그러면 Kafka 브로커의 EBS가 붙지 못해 영구 Pending이 된다.
# AZ별 노드그룹이면 대체 노드가 항상 같은 AZ에 뜬다.

data "aws_partition" "current" {}

# ---------------------------------------------------------------------------
# 클러스터 IAM
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "cluster_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${var.cluster_name}-cluster"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

# ---------------------------------------------------------------------------
# 클러스터
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 봉투 암호화(Kubernetes API 데이터) — 고객 관리형 KMS 키를 **일부러 쓰지 않는다**
#
# EKS는 Kubernetes 1.28 이상에서 **모든 API 데이터를 AWS 소유 KMS 키로 기본 봉투 암호화**한다.
# 별도 설정도, 권한도, 추가 비용도 없다. 즉 "암호화가 꺼져 있다"는 전제는 성립하지 않는다.
#   https://docs.aws.amazon.com/eks/latest/userguide/envelope-encryption.html
#
# 고객 관리형 키(CMK)를 얹으면 얻는 것은 암호화 자체가 아니라 **키 정책·감사·폐기 통제권**이다.
# 이 프로젝트에는 그걸 요구하는 근거(규정 준수, 키 관리자와 클러스터 관리자 분리 등)가 없다.
#
# 반면 비용은 분명하다:
#   - 키가 비활성화되면 클러스터가 즉시 degraded, **삭제되면 복구 불가**(AWS 문서 명시).
#     이 스택은 데모마다 만들고 지우므로 그 사고 확률이 오히려 높다.
#   - 월 $1 + 요청 요금, 키 정책 설계, destroy 순서 의존.
#
# 필요해지면 나중에 붙일 수 있다 — 기존 클러스터에도 AssociateEncryptionConfig로 연결된다.
# 다만 **한번 연결하면 해제하거나 다른 키로 바꿀 수 없다**는 점이 진짜 비가역성이다.
#   https://docs.aws.amazon.com/eks/latest/userguide/enable-kms.html
#
# 참고: CMK를 쓸 때 kms:DescribeKey·kms:CreateGrant가 필요한 주체는 **CreateCluster를 호출하는
# principal**(= Terraform 실행 주체)이지 클러스터 IAM 역할이 아니다. 초안은 이 권한을 클러스터
# 역할에 붙였는데, 위치가 틀린 설계였다.
# ---------------------------------------------------------------------------

# 로그 그룹을 먼저 만들어 **보존 기간을 못 박는다.** EKS가 알아서 만들게 두면 보존이
# "만료 없음"이라, 클러스터를 지운 뒤에도 로그 저장 요금이 계속 남는다(데모 전제와 어긋난다).
resource "aws_cloudwatch_log_group" "cluster" {
  count = length(var.cluster_log_types) > 0 ? 1 : 0

  name              = "/aws/eks/${var.cluster_name}/cluster"
  retention_in_days = var.cluster_log_retention_days
  tags              = var.tags
}

resource "aws_eks_cluster" "this" {
  name     = var.cluster_name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

  # ② 컨트롤플레인 로그 — 롤링 무중단 실증의 **증거**다.
  # api/audit이 없으면 "무중단이었다"를 애플리케이션 로그로만 주장하게 된다. scheduler·
  # controllerManager는 파드 재배치가 왜 그렇게 일어났는지를 설명한다.
  # CloudWatch Logs 수집·보존 요금이 붙지만 데모 기간(수 시간)에는 미미하다.
  enabled_cluster_log_types = var.cluster_log_types

  vpc_config {
    # 컨트롤플레인 ENI는 프라이빗 App 서브넷에 둔다.
    subnet_ids = var.private_app_subnet_ids

    # 퍼블릭 엔드포인트를 켜는 이유: 데모라 로컬에서 kubectl/ArgoCD 부트스트랩을 해야 한다.
    # 대신 var.public_access_cidrs로 접근 출발지를 좁힐 수 있게 뒀다(기본은 전체 개방).
    endpoint_private_access = true
    endpoint_public_access  = true
    public_access_cidrs     = var.public_access_cidrs
  }

  access_config {
    authentication_mode = "API_AND_CONFIG_MAP"
    # apply를 실행한 주체가 곧바로 kubectl을 쓸 수 있어야 부트스트랩이 이어진다.
    bootstrap_cluster_creator_admin_permissions = true
  }

  tags = var.tags

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_cloudwatch_log_group.cluster, # 보존 기간이 정해진 로그 그룹을 EKS가 재사용하게
  ]
}

# ---------------------------------------------------------------------------
# IRSA용 OIDC 공급자
# ---------------------------------------------------------------------------
data "tls_certificate" "oidc" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]
  tags            = var.tags
}

# ---------------------------------------------------------------------------
# 노드 IAM
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.cluster_name}-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    # ECR에서 이미지를 pull하기 위해 필요하다(Phase 2에서 올린 이미지).
    "AmazonEC2ContainerRegistryReadOnly",
  ])

  role       = aws_iam_role.node.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/${each.key}"
}

# ---------------------------------------------------------------------------
# 노드그룹 — AZ별 1개
# ---------------------------------------------------------------------------
resource "aws_eks_node_group" "this" {
  for_each = var.private_app_subnet_id_by_az

  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.cluster_name}-${each.key}"
  node_role_arn   = aws_iam_role.node.arn

  # 이 노드그룹은 이 AZ 서브넷 하나만 쓴다 — 대체 노드가 다른 AZ로 새지 않게 한다.
  subnet_ids = [each.value]

  instance_types = [var.node_instance_type]

  scaling_config {
    min_size     = var.node_min_size
    desired_size = var.node_desired_size
    max_size     = var.node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  labels = {
    "flowticket.io/az" = each.key
  }

  tags = merge(var.tags, {
    # Cluster Autoscaler가 ASG를 자동 탐색하는 데 쓰는 태그.
    # ⚠️ 관리형 노드그룹의 tags가 하위 ASG까지 전파되는지는 프로바이더 버전에 따라 다르다.
    #    apply 후 ASG에 이 태그가 실제로 붙었는지 확인하고, 없으면 ASG에 직접 붙여야
    #    CA가 이 노드그룹을 인식하지 못한다.
    "k8s.io/cluster-autoscaler/enabled"             = "true"
    "k8s.io/cluster-autoscaler/${var.cluster_name}" = "owned"
  })

  lifecycle {
    # desired_size는 Cluster Autoscaler가 런타임에 바꾼다. Terraform이 이를 드리프트로
    # 보고 매번 되돌리면 확장이 취소되므로 무시한다.
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ---------------------------------------------------------------------------
# 애드온
# ---------------------------------------------------------------------------
# metrics-server가 여기 있는 이유: HPA는 metrics-server 없이는 CPU를 못 읽어
# `cpu: <unknown>` 상태로 **스케일 판단 자체를 못 한다**(TS-019). 그 사건을 한 번 겪고
# 손으로 설치해 닫았는데, 설치가 IaC 밖에 있어서 **클러스터를 재생성하니 그대로 재발했다**
# (2026-08-11). ArgoCD의 HPA health check가 ScalingActive=False를 Degraded로 잡아
# 앱 전체가 Degraded로 표시됐고, 원인을 찾는 데 시간이 걸렸다.
#
# "고쳤다"와 "재현 가능하게 고쳤다"는 다르다. 그래서 관리형 애드온으로 못박는다 —
# IRSA가 필요 없으므로 아래 for_each 목록에 넣으면 충분하다.
resource "aws_eks_addon" "this" {
  for_each = toset(["vpc-cni", "coredns", "kube-proxy", "metrics-server"])

  cluster_name = aws_eks_cluster.this.name
  addon_name   = each.key

  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = var.tags

  # 애드온 Pod가 스케줄될 노드가 있어야 한다.
  depends_on = [aws_eks_node_group.this]
}

# EBS CSI는 IRSA가 필요해 따로 둔다 — Kafka 브로커의 PVC가 이 드라이버로 만들어진다.
resource "aws_eks_addon" "ebs_csi" {
  cluster_name             = aws_eks_cluster.this.name
  addon_name               = "aws-ebs-csi-driver"
  service_account_role_arn = aws_iam_role.ebs_csi.arn

  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = var.tags

  depends_on = [aws_eks_node_group.this]
}

# ---------------------------------------------------------------------------
# 노드 → 데이터 계층 접근용 SG 참조
# ---------------------------------------------------------------------------
# EKS가 만든 클러스터 SG를 RDS·ElastiCache의 ingress 출발지로 쓴다.
# CIDR 대신 SG를 참조하면 "이 클러스터의 노드만" 이라는 의도가 그대로 드러난다.
