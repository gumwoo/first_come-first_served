output "security_group_id" {
  description = "Interface 엔드포인트 ENI의 SG. 엔드포인트를 안 만들면 null."
  value       = try(aws_security_group.endpoints[0].id, null)
}

output "s3_endpoint_id" {
  value = try(aws_vpc_endpoint.s3[0].id, null)
}
