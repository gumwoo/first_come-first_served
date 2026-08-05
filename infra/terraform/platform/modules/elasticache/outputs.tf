output "primary_endpoint" {
  description = "쓰기 엔드포인트. 단일 노드에서도 이 값을 쓴다."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  value = aws_elasticache_replication_group.this.port
}

output "security_group_id" {
  value = aws_security_group.redis.id
}
