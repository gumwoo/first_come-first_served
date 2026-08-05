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
resource "aws_eks_cluster" "this" {
  name     = var.cluster_name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

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

  depends_on = [aws_iam_role_policy_attachment.cluster]
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
resource "aws_eks_addon" "this" {
  for_each = toset(["vpc-cni", "coredns", "kube-proxy"])

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
