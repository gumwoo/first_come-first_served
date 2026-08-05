output "endpoint" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "username" {
  value = aws_db_instance.this.username
}

output "master_user_secret_arn" {
  description = <<-EOT
    RDS가 관리하는 마스터 자격증명 시크릿의 ARN.
    비밀번호 자체는 Terraform이 알지 못하므로 state에도 남지 않는다.
    앱에는 External Secrets가 이 ARN을 읽어 K8s Secret으로 넣는다(ADR-009).
  EOT
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "security_group_id" {
  value = aws_security_group.db.id
}
