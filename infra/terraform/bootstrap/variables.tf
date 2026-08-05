variable "region" {
  description = "ACM 인증서는 ALB와 같은 리전이어야 한다."
  type        = string
  default     = "ap-northeast-2"
}

variable "domain_name" {
  description = "루트 도메인. Hosted Zone은 Registrar가 만든 것을 data로 참조한다."
  type        = string
  default     = "flow-ticket.com"
}

# ---------------------------------------------------------------------------
# ECR
# ---------------------------------------------------------------------------
variable "ecr_keep_last_images" {
  description = "태그가 git SHA라 이미지가 계속 쌓인다. 최근 N개만 보관한다."
  type        = number
  default     = 20
}

variable "ecr_image_tag_mutability" {
  description = <<-EOT
    MUTABLE 기본. workflow_dispatch로 재실행하면 같은 SHA 태그를 다시 push하므로
    IMMUTABLE이면 실패한다(재실행 시 덮어쓰기가 실제로 일어난다).
    서로 다른 커밋이 같은 태그를 쓰는 일은 없어(태그 = git SHA) 재사용 위험은 제한적이다.
  EOT
  type        = string
  default     = "MUTABLE"

  validation {
    condition     = contains(["MUTABLE", "IMMUTABLE"], var.ecr_image_tag_mutability)
    error_message = "MUTABLE 또는 IMMUTABLE 이어야 한다."
  }
}

# ---------------------------------------------------------------------------
# GitHub OIDC
# ---------------------------------------------------------------------------
variable "github_actions_role_name" {
  description = "콘솔에서 만든 역할 이름과 정확히 같아야 한다(import 대상)."
  type        = string
  default     = "flowticket-gha-ecr"
}

variable "github_oidc_subjects" {
  description = <<-EOT
    이 역할을 맡을 수 있는 GitHub 워크플로의 sub 조건(StringLike).

    현재 값은 **콘솔에 설정돼 있던 것과 동일**하다. import를 무변경(no-op)으로 만들어
    "import 자체가 제대로 됐는가"와 "조건을 좁혀도 되는가"를 분리해 검증하기 위해서다.
    두 변수를 한 번에 바꾸면 파이프라인이 깨졌을 때 원인을 가릴 수 없다.

    ⚠️ `:*`는 이 저장소의 **모든 브랜치·태그·PR**이 역할을 맡을 수 있다는 뜻이라 느슨하다.
    `ref:refs/heads/main`으로 좁히는 것은 **별도 변경**으로 진행하고, 적용 직후
    `gh workflow run image.yml`로 파이프라인 생존을 확인한다(TODO).
  EOT
  type        = list(string)
  default     = ["repo:gumwoo/first_come-first_served:*"]

  validation {
    condition = alltrue([
      for s in var.github_oidc_subjects : can(regex("^repo:[^:]+/[^:]+:", s))
    ])
    error_message = "sub는 'repo:<owner>/<repo>:...' 형식이어야 한다(모든 저장소 허용 방지)."
  }
}

variable "github_oidc_thumbprints" {
  description = <<-EOT
    콘솔에 실제로 등록된 값. 바꾸면 OIDC 검증에 영향이 갈 수 있어 현실과 일치시킨다.
    (AWS는 GitHub OIDC의 인증서를 자체 검증하므로 이 값은 사실상 형식값이다.)
  EOT
  type        = list(string)
  default     = ["ab9d0263244dd0326eb67015705a667e79cfe998"]
}

variable "tags" {
  type = map(string)
  default = {
    Project   = "flowticket"
    ManagedBy = "terraform"
    Layer     = "bootstrap"
  }
}
