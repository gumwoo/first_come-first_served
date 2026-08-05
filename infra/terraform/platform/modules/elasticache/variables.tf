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
    기본값 true (ADR-012 §10). 켜면 복제본이 다른 AZ에 생기고 자동 페일오버가 켜진다.

    근거는 RDS와 다르다. Redis가 죽어도 초과판매·주문 상태 같은 정합성은 깨지지 않는다
    (진실원은 DB — ADR-003/006/008). 대신 대기열 순번과 SSE 팬아웃이 사라진다.
    선착순 대기열이 이 서비스의 간판 기능이라 "정합성은 무사한데 서비스는 멈춘" 상태가 된다.

    ⚠️ Redis 복제는 비동기다. 페일오버 시 가장 최근 쓰기 일부는 유실될 수 있다 —
    가용성이 올라가는 것이지 무손실이 되는 것이 아니다.
  EOT
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
