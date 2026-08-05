variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "allowed_security_group_id" {
  description = "접속을 허용할 SG(EKS 클러스터 SG)"
  type        = string
}

variable "engine_version" {
  description = "로컬·CI가 redis:7.4이므로 메이저를 맞춘다."
  type        = string
  default     = "7.1"
}

variable "node_type" {
  type    = string
  default = "cache.t4g.micro"
}

variable "multi_az" {
  description = <<-EOT
    기본값 false — 데모 환경의 절충이다.
    켜면 복제본이 다른 AZ에 생기고 자동 페일오버가 활성화된다(비용 약 2배).
    ⚠️ RDS와 같은 논점: 앱·Kafka는 3 AZ인데 Redis가 단일 AZ면 그 AZ 장애 시
    대기열·SSE 팬아웃이 멈춘다. 판매 정합성은 DB가 지키므로 데이터 손상은 없다.
  EOT
  type        = bool
  default     = false
}

variable "tags" {
  type    = map(string)
  default = {}
}
