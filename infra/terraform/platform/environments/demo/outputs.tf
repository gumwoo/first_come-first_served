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
  description = "자격증명은 이 시크릿에만 있다. 값은 output으로 내보내지 않는다."
  value       = module.rds.secret_arn
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
