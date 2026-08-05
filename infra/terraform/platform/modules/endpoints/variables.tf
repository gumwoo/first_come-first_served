variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  description = "엔드포인트 SG의 ingress 허용 범위"
  type        = string
}

variable "subnet_ids" {
  description = "Interface 엔드포인트 ENI를 둘 서브넷(프라이빗 App)"
  type        = list(string)
}

variable "route_table_ids" {
  description = "S3 Gateway 엔드포인트를 연결할 라우팅 테이블"
  type        = list(string)
}

variable "enabled" {
  description = <<-EOT
    엔드포인트 전체 on/off. NAT가 있으므로 꺼도 시스템은 동작한다 —
    비용/복잡도를 줄이고 싶을 때 false로 둔다.
  EOT
  type        = bool
  default     = true
}

variable "interface_services" {
  description = <<-EOT
    Interface 엔드포인트로 만들 서비스. AZ마다 ENI가 생기고 시간당 과금되므로
    1차는 ECR만 둔다. 필요해지면 logs·secretsmanager·sts를 추가한다.
  EOT
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr"]
}

variable "tags" {
  type    = map(string)
  default = {}
}
