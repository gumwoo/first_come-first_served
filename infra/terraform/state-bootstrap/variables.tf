variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "create_lock_table" {
  description = <<-EOT
    DynamoDB 잠금 테이블 생성 여부.
    Terraform 1.10+ 의 S3 네이티브 잠금(use_lockfile)을 쓰면 false로 둘 수 있다.
  EOT
  type        = bool
  default     = true
}

variable "noncurrent_version_retention_days" {
  description = "이전 state 버전 보관 기간. 복구 여지는 남기되 무한히 쌓이지 않게 한다."
  type        = number
  default     = 90
}

variable "tags" {
  type = map(string)
  default = {
    Project   = "flowticket"
    ManagedBy = "terraform"
    Layer     = "state-bootstrap"
  }
}
