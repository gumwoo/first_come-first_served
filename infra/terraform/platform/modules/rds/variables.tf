variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "프라이빗 데이터 서브넷 3개"
  type        = list(string)
}

variable "allowed_security_group_id" {
  description = "접속을 허용할 SG(EKS 클러스터 SG)"
  type        = string
}

variable "engine_version" {
  description = "로컬·CI가 postgres:16이므로 메이저를 맞춘다."
  type        = string
  default     = "16"
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "allocated_storage" {
  type    = number
  default = 20
}

variable "db_name" {
  type    = string
  default = "flowticket"
}

variable "username" {
  type    = string
  default = "flowticket"
}

variable "multi_az" {
  description = <<-EOT
    기본값 false — 데모 환경의 절충이다.
    ⚠️ 앱과 Kafka는 3 AZ에 분산하는데 DB가 단일 AZ면, AZ 장애 시 결국 전체가 멈춘다.
    NAT를 AZ별로 둔 것과 같은 일관성 논점이 여기에도 있다(ADR-012 §2 참고).
    켜면 인스턴스 비용이 약 2배가 되고 생성·삭제 시간도 늘어난다.
  EOT
  type        = bool
  default     = false
}

variable "backup_retention_period" {
  description = "0이면 자동 백업을 하지 않는다(데모 기본값)."
  type        = number
  default     = 0
}

variable "tags" {
  type    = map(string)
  default = {}
}
