# VPC — 3 AZ, 계층별 서브넷 3종, AZ별 NAT.
#
# 설계 근거는 ADR-012, 구조는 docs/deployment/terraform-design.md §2.
# 핵심: NAT를 AZ마다 두고 "같은 AZ의 NAT로만" 내보낸다. 교차 AZ로 라우팅하면
# AZ 격리가 깨지고(한 AZ 장애가 다른 AZ의 PG 승인까지 차단) 데이터 전송료도 붙는다.

locals {
  # az => index. 서브넷 CIDR 리스트와 AZ를 짝지어 for_each를 돌리기 위한 맵.
  # count 대신 for_each를 쓰는 이유: AZ 목록 중간이 바뀌어도 뒤 리소스가 재생성되지 않는다.
  az_index = { for idx, az in var.azs : az => idx }
}

resource "aws_vpc" "this" {
  cidr_block = var.cidr

  # Interface 엔드포인트의 프라이빗 DNS와 EKS가 둘 다 요구한다.
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = merge(var.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-igw" })
}

# ---------------------------------------------------------------------------
# 서브넷 3계층
# ---------------------------------------------------------------------------

# ALB와 NAT만 들어간다. 워크로드는 절대 여기 두지 않는다.
resource "aws_subnet" "public" {
  for_each = local.az_index

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = var.public_subnet_cidrs[each.value]
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name = "${var.name}-public-${each.key}"
    # ALB Controller가 internet-facing LB를 붙일 서브넷을 이 태그로 찾는다.
    "kubernetes.io/role/elb"                    = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  })
}

# EKS 노드 + Pod IP. VPC CNI가 Pod에 이 서브넷 IP를 직접 할당하므로 넓어야 한다(/19).
resource "aws_subnet" "private_app" {
  for_each = local.az_index

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = var.private_app_subnet_cidrs[each.value]

  tags = merge(var.tags, {
    Name                                        = "${var.name}-private-app-${each.key}"
    "kubernetes.io/role/internal-elb"           = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  })
}

# RDS·ElastiCache 전용. 기본 라우트가 없어 인터넷으로 나가지도 들어오지도 못한다.
resource "aws_subnet" "private_data" {
  for_each = local.az_index

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = var.private_data_subnet_cidrs[each.value]

  tags = merge(var.tags, { Name = "${var.name}-private-data-${each.key}" })
}

# ---------------------------------------------------------------------------
# NAT — AZ별 1개
# ---------------------------------------------------------------------------

# ⚠️ EIP 쿼터는 5인데 여기서 3을 쓴다. destroy에서 EIP가 남으면 다음 apply가
# 3+3 > 5로 실패한다(증상이 쿼터 에러라 원인이 헷갈린다). terraform-design §6 참조.
resource "aws_eip" "nat" {
  for_each = local.az_index

  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-nat-${each.key}" })
}

resource "aws_nat_gateway" "this" {
  for_each = local.az_index

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id

  tags = merge(var.tags, { Name = "${var.name}-nat-${each.key}" })

  # IGW가 먼저 붙어야 NAT가 정상 동작한다(Terraform이 순서를 추론하지 못한다).
  depends_on = [aws_internet_gateway.this]
}

# ---------------------------------------------------------------------------
# 라우팅
# ---------------------------------------------------------------------------

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = local.az_index

  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public.id
}

# App 서브넷은 AZ마다 라우팅 테이블을 따로 둔다 — 같은 AZ의 NAT를 가리키기 위해서다.
resource "aws_route_table" "private_app" {
  for_each = local.az_index

  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-private-app-${each.key}" })
}

resource "aws_route" "private_app_nat" {
  for_each = local.az_index

  route_table_id         = aws_route_table.private_app[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[each.key].id
}

resource "aws_route_table_association" "private_app" {
  for_each = local.az_index

  subnet_id      = aws_subnet.private_app[each.key].id
  route_table_id = aws_route_table.private_app[each.key].id
}

# Data 서브넷에는 기본 라우트를 넣지 않는다 — VPC 내부 통신만 가능하다.
# 라우팅 테이블 자체는 두는데, S3 Gateway 엔드포인트를 여기에도 연결하기 위해서다.
resource "aws_route_table" "private_data" {
  for_each = local.az_index

  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-private-data-${each.key}" })
}

resource "aws_route_table_association" "private_data" {
  for_each = local.az_index

  subnet_id      = aws_subnet.private_data[each.key].id
  route_table_id = aws_route_table.private_data[each.key].id
}
