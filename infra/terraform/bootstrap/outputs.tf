# platform이 terraform_remote_state로 읽는 값들.
# 민감값은 여기로 내보내지 않는다 — state 파일은 평문이다.

output "hosted_zone_id" {
  description = "Registrar가 만든 Zone을 data로 읽어 전달한다(Terraform이 소유하지 않음)."
  value       = data.aws_route53_zone.this.zone_id
}

output "domain_name" {
  value = var.domain_name
}

output "certificate_arn" {
  description = <<-EOT
    검증까지 끝난 인증서 ARN. validation 리소스를 거쳐 내보내므로,
    이 값이 나왔다는 것은 ALB에 붙일 수 있는 상태라는 뜻이다.
  EOT
  value       = aws_acm_certificate_validation.this.certificate_arn
}

output "ecr_repository_urls" {
  value = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "github_actions_role_arn" {
  description = "GitHub 시크릿 AWS_ROLE_ARN 과 같아야 한다."
  value       = aws_iam_role.github_actions.arn
}
