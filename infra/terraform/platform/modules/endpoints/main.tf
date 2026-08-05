# VPC 엔드포인트 — AWS 서비스 트래픽을 NAT 경로에서 분리한다.
#
# ⚠️ 이건 "없으면 실패"가 아니라 개선이다. NAT가 3개 있으므로 엔드포인트가 없어도
# 시스템은 동작한다(terraform-design §2). 그래서 var.enabled로 통째로 끌 수 있게 뒀다.
#
# S3는 Gateway 유형이라 시간당 요금이 없고, ECR 이미지 레이어가 S3 경로를 쓰므로
# 이미지 pull 트래픽의 상당 부분이 NAT를 우회한다 — 넣는 이득이 가장 크다.
# 반면 Interface 유형은 AZ마다 ENI가 생기고 시간당 과금된다.

locals {
  enabled = var.enabled ? 1 : 0

  # 서비스별로 켜고 끌 수 있게 집합으로 다룬다.
  interface_services = var.enabled ? toset(var.interface_services) : toset([])
}

# ---------------------------------------------------------------------------
# S3 — Gateway (무료)
# ---------------------------------------------------------------------------
resource "aws_vpc_endpoint" "s3" {
  count = local.enabled

  vpc_id            = var.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = var.route_table_ids

  tags = merge(var.tags, { Name = "${var.name}-s3" })
}

# ---------------------------------------------------------------------------
# Interface 엔드포인트 (ECR API/DKR 등)
# ---------------------------------------------------------------------------

# 엔드포인트 ENI가 받는 SG. VPC 내부에서 온 443만 허용한다.
resource "aws_security_group" "endpoints" {
  count = length(local.interface_services) > 0 ? 1 : 0

  name        = "${var.name}-vpce"
  description = "VPC interface endpoints - HTTPS from within VPC"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS from VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  tags = merge(var.tags, { Name = "${var.name}-vpce" })
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_services

  vpc_id            = var.vpc_id
  service_name      = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type = "Interface"
  subnet_ids        = var.subnet_ids

  security_group_ids = [aws_security_group.endpoints[0].id]

  # 이게 꺼져 있으면 Pod가 기존 퍼블릭 엔드포인트 이름을 그대로 해석해 NAT로 나간다
  # (엔드포인트를 만든 의미가 없어진다). VPC의 enable_dns_* 가 켜져 있어야 동작한다.
  private_dns_enabled = true

  tags = merge(var.tags, { Name = "${var.name}-${each.key}" })
}
