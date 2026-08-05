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
    기본값 true (ADR-012 §10).
    DB는 이 시스템의 진실원이다 — 정합성 설계 전체(조건부 UPDATE·상태 전이·아웃박스)가
    "DB가 살아있다"를 전제로 선다. 단일 AZ면 그 AZ 장애가 곧 전체 중단이라,
    단일 NAT(일부 기능 마비)보다 파급이 크다.

    ⚠️ 켠다고 무손실이 되는 것은 아니다. 페일오버에는 통상 수십 초~2분이 걸리고
    그동안 쓰기가 막힌다 — "켰으니 안전"이 아니라 "중단 시간이 짧아진다"가 정확하다.
    비용은 인스턴스가 약 2배이고, 생성·삭제 시간이 늘어 apply/destroy 반복이 느려진다.
  EOT
  type        = bool
  default     = true
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
