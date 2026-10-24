# EKS: 클러스터 + AZ별 노드그룹 3개 + 애드온.
# 노드그룹을 AZ마다 나누는 이유는 EBS다. 대체 노드가 다른 AZ에 뜨면 PVC를 쓰는 파드(Prometheus)의
# EBS가 붙지 못해 영구 Pending이 된다(ADR-012 §3).

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
# 봉투 암호화에 고객 관리형 KMS 키(CMK)를 일부러 쓰지 않는다. CMK는 삭제되면 클러스터가 복구 불가다.
# 근거·붙이는 방법은 platform/README.md "봉투 암호화".

# 로그 그룹을 먼저 만들어 보존 기간을 못 박는다. EKS가 알아서 만들게 두면 보존이
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

  # 컨트롤플레인 로그는 롤링 무중단 실증의 증거다. api/audit이 없으면 "무중단이었다"를 앱 로그로만
  # 주장하게 되고, scheduler·controllerManager는 파드 재배치 이유를 설명한다.
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
    "AmazonEC2ContainerRegistryReadOnly",
  ])

  role       = aws_iam_role.node.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/${each.key}"
}

# ---------------------------------------------------------------------------
# 노드그룹: AZ별 1개
# ---------------------------------------------------------------------------
resource "aws_eks_node_group" "this" {
  for_each = var.private_app_subnet_id_by_az

  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.cluster_name}-${each.key}"
  node_role_arn   = aws_iam_role.node.arn

  # 이 노드그룹은 이 AZ 서브넷 하나만 쓴다. 대체 노드가 다른 AZ로 새지 않게 한다.
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
    # Cluster Autoscaler의 ASG 자동 탐색 태그. 관리형 노드그룹 tags가 ASG까지 전파되는지는 프로바이더
    # 버전에 따라 다르다. apply 후 ASG에 붙었는지 확인하고, 없으면 ASG에 직접 붙인다(없으면 CA가 못 찾는다).
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
# metrics-server가 없으면 HPA가 CPU를 못 읽어 스케일 판단을 못 한다(TS-019). 클러스터를 재생성해도
# 빠지지 않게 EKS Add-on으로 둔다. IRSA가 필요 없어 이 목록으로 충분하다(EBS CSI는 IRSA 때문에 따로 둔다).
# metrics-server 버전은 고정한다. eksbuild.12가 protobuf /openapi/v2를 503으로 만들어 helm·ArgoCD가 멈췄다(TS-050).
# 올릴 때는 TS-050의 확인 명령으로 새 클러스터에서 200인지 먼저 본다.
locals {
  eks_addon_versions = {
    "vpc-cni"        = null
    "coredns"        = null
    "kube-proxy"     = null
    "metrics-server" = "v0.9.0-eksbuild.11"
  }

  # coredns 2개를 노드 단위로 강제 분산한다(한 노드를 잃으면 클러스터 DNS가 함께 멈춘다, 추론). 애드온 기본값은
  # hostname anti-affinity "preferred"뿐이라 2개가 한 노드에 놓인 적이 있다(IMP-034 §8).
  # 노드그룹이 AZ마다 최소 1대(node_min_size)라 노드 수 때문에 Pending이 되지는 않는다(계산). 기본 affinity는 덮어쓰지 않는다.
  eks_addon_configuration = {
    "coredns" = jsonencode({
      topologySpreadConstraints = [{
        maxSkew           = 1
        topologyKey       = "kubernetes.io/hostname"
        whenUnsatisfiable = "DoNotSchedule"
        labelSelector     = { matchLabels = { "k8s-app" = "kube-dns" } }
      }]
    })
  }
}

resource "aws_eks_addon" "this" {
  for_each = local.eks_addon_versions

  cluster_name  = aws_eks_cluster.this.name
  addon_name    = each.key
  addon_version = each.value

  configuration_values = lookup(local.eks_addon_configuration, each.key, null)

  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = var.tags

  # 애드온 Pod가 스케줄될 노드가 있어야 한다.
  depends_on = [aws_eks_node_group.this]
}

# EBS CSI는 IRSA가 필요해 따로 둔다. Prometheus의 PVC(gp2 20Gi — kube-prometheus-stack.values.yaml)가 이 드라이버로 만들어진다.
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
