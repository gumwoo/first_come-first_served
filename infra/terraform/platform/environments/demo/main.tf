# FlowTicket 데모 환경 — 유일한 환경이다.
#
# 상시 운영하지 않는다: 필요할 때 apply → 실증·촬영 → destroy(ADR-012 §8).
# destroy는 순서가 있다 — Ingress(ALB) → Kafka CR/PVC → EBS 확인 → terraform destroy.
# 순서를 어기면 VPC 삭제가 막히거나 EBS·EIP가 남아 과금이 계속된다.

locals {
  name = "flowticket"

  tags = {
    Project     = "flowticket"
    Environment = "demo"
    ManagedBy   = "terraform"
  }
}

module "vpc" {
  source = "../../modules/vpc"

  name         = local.name
  cidr         = var.vpc_cidr
  azs          = var.azs
  cluster_name = var.cluster_name

  public_subnet_cidrs       = var.public_subnet_cidrs
  private_app_subnet_cidrs  = var.private_app_subnet_cidrs
  private_data_subnet_cidrs = var.private_data_subnet_cidrs

  tags = local.tags
}

module "endpoints" {
  source = "../../modules/endpoints"

  name     = local.name
  region   = var.region
  vpc_id   = module.vpc.vpc_id
  vpc_cidr = module.vpc.vpc_cidr

  subnet_ids      = module.vpc.private_app_subnet_ids
  route_table_ids = module.vpc.private_route_table_ids

  enabled = var.vpc_endpoints_enabled

  tags = local.tags
}

module "eks" {
  source = "../../modules/eks"

  cluster_name       = var.cluster_name
  kubernetes_version = var.kubernetes_version

  private_app_subnet_ids      = module.vpc.private_app_subnet_ids
  private_app_subnet_id_by_az = module.vpc.private_app_subnet_id_by_az
  public_access_cidrs         = var.cluster_public_access_cidrs

  node_instance_type = var.node_instance_type
  node_min_size      = var.node_min_size
  node_desired_size  = var.node_desired_size
  node_max_size      = var.node_max_size

  cluster_log_types          = var.cluster_log_types
  cluster_log_retention_days = var.cluster_log_retention_days

  tags = local.tags
}

module "rds" {
  source = "../../modules/rds"

  name       = local.name
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_data_subnet_ids

  allowed_security_group_id = module.eks.cluster_security_group_id

  multi_az = var.db_multi_az

  tags = local.tags
}

module "redis" {
  source = "../../modules/elasticache"

  name       = local.name
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_data_subnet_ids

  allowed_security_group_id = module.eks.cluster_security_group_id

  multi_az           = var.redis_multi_az
  transit_encryption = var.redis_transit_encryption

  tags = local.tags
}
