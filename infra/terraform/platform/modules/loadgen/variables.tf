variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "퍼블릭 서브넷. 발생기는 ALB 공인 경로로 들어가야 한다(loadtest-100k-plan §4)."
  type        = list(string)
}

variable "instance_count" {
  description = "발생기 대수. 0이면 아무것도 만들지 않는다 — 측정 세션에서만 loadtest.tfvars로 켠다."
  type        = number
  default     = 0

  validation {
    condition     = var.instance_count >= 0 && floor(var.instance_count) == var.instance_count
    error_message = "instance_count는 0 이상의 정수여야 한다."
  }
}

variable "instance_type" {
  description = "발생기 인스턴스 타입. 상한은 계획서 §4에 고정한다."
  type        = string
  default     = "c6i.xlarge"
}

variable "k6_version" {
  description = "로컬 스모크에서 동작을 확인한 버전과 맞춘다. 버전이 다르면 그 확인이 무의미해진다."
  type        = string
  default     = "2.1.0"
}

variable "repo_url" {
  description = "발생기 스크립트를 받을 저장소. 실행할 커밋은 측정 세션에서 고정한다."
  type        = string
  default     = "https://github.com/gumwoo/first_come-first_served.git"
}

variable "tags" {
  type    = map(string)
  default = {}
}
