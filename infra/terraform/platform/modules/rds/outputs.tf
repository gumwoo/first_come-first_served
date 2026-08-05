output "endpoint" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "secret_arn" {
  description = <<-EOT
    자격증명이 담긴 Secrets Manager 시크릿의 ARN.
    값 자체는 절대 output으로 내보내지 않는다 — state·CI 로그로 새어 나가기 때문이다.
    앱에는 External Secrets가 이 ARN을 읽어 K8s Secret으로 넣는다(ADR-009).
  EOT
  value       = aws_secretsmanager_secret.master.arn
}

output "security_group_id" {
  value = aws_security_group.db.id
}
