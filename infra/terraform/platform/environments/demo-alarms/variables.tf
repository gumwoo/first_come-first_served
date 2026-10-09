variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "alb_arn_suffix" {
  description = <<-EOT
    알람을 붙일 ALB의 ARN 접미사(app/<이름>/<id>) — CloudWatch LoadBalancer 차원 값.
    ALB는 AWS Load Balancer Controller가 Ingress를 보고 만들어 Terraform이 모른다. scripts/alb-alarms.sh가
    Ingress의 ALB 주소로 찾아 넘긴다. data 소스로 찾지 않는 이유는 main.tf 머리말.
  EOT
  type        = string

  validation {
    condition     = can(regex("^app/[^/]+/[0-9a-f]+$", var.alb_arn_suffix))
    error_message = "alb_arn_suffix는 app/<이름>/<id> 형식이어야 한다(ALB ARN의 loadbalancer/ 뒤)."
  }
}

variable "target_group_arn_suffixes" {
  description = "정상 대상 수를 볼 Target Group ARN 접미사(targetgroup/<이름>/<id>) 목록 — web·api 서비스마다 하나."
  type        = list(string)

  validation {
    condition     = alltrue([for t in var.target_group_arn_suffixes : can(regex("^targetgroup/[^/]+/[0-9a-f]+$", t))])
    error_message = "target_group_arn_suffixes의 원소는 targetgroup/<이름>/<id> 형식이어야 한다."
  }
}

variable "alarm_email" {
  description = "알람을 받을 이메일. 비우면 SNS 토픽만 만든다(구독은 수신자가 확인 메일을 눌러야 활성화된다)."
  type        = string
  default     = ""
}

variable "elb_5xx_threshold" {
  description = "1분 동안 ALB가 직접 만든 5xx(대상에 닿지 못한 502·503·504 등) 수의 경보 임계."
  type        = number
  default     = 10
}

variable "runbook_url" {
  description = "알람 설명에 넣을 대응 문서. ALB 5xx가 앱 5xx 없이 나던 사건 기록(TS-035)."
  type        = string
  default     = "https://github.com/gumwoo/first_come-first_served/blob/main/docs/troubleshooting/TS-035-rolling-deregistration-race.md"
}
