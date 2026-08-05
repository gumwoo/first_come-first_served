output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "kubeconfig_command" {
  description = "apply 후 이 명령으로 kubectl 컨텍스트를 붙인다."
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "irsa_role_arns" {
  description = "Helm 설치 시 서비스 어카운트에 annotate할 역할 ARN."
  value       = module.eks.irsa_role_arns
}

output "db_endpoint" {
  value = module.rds.endpoint
}

output "db_secret_arn" {
  description = "RDS가 관리하는 자격증명 시크릿의 ARN. 비밀번호는 Terraform state에 없다."
  value       = module.rds.master_user_secret_arn
}

output "redis_endpoint" {
  value = module.redis.primary_endpoint
}

output "nat_public_ips" {
  description = "외부 API(PG·KOPIS)가 허용목록을 요구할 때 쓴다."
  value       = module.vpc.nat_public_ips
}

output "private_app_subnet_ids" {
  value = module.vpc.private_app_subnet_ids
}

output "domain_name" {
  description = "bootstrap에서 읽어 온 값. Ingress 호스트 규칙에 쓴다."
  value       = local.domain_name
}

output "certificate_arn" {
  description = "ALB Ingress annotation에 넣을 ACM 인증서(검증 완료 상태)."
  value       = local.certificate_arn
}

output "hosted_zone_id" {
  value = local.hosted_zone_id
}
