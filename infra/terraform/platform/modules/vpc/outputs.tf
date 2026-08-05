output "vpc_id" {
  value = aws_vpc.this.id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "AZ 순서 보장이 필요하므로 var.azs 순서로 정렬해 반환한다."
  value       = [for az in var.azs : aws_subnet.public[az].id]
}

output "private_app_subnet_ids" {
  value = [for az in var.azs : aws_subnet.private_app[az].id]
}

output "private_data_subnet_ids" {
  value = [for az in var.azs : aws_subnet.private_data[az].id]
}

output "private_app_subnet_id_by_az" {
  description = "AZ별 노드그룹이 자기 AZ 서브넷만 쓰도록 맵으로도 노출한다."
  value       = { for az in var.azs : az => aws_subnet.private_app[az].id }
}

output "private_route_table_ids" {
  description = "S3 Gateway 엔드포인트를 붙일 대상(app + data 전부)."
  value = concat(
    [for az in var.azs : aws_route_table.private_app[az].id],
    [for az in var.azs : aws_route_table.private_data[az].id],
  )
}

output "nat_public_ips" {
  description = "외부 API(PG·KOPIS) 허용목록이 필요할 때 쓴다."
  value       = [for az in var.azs : aws_eip.nat[az].public_ip]
}
