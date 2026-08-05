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
    MUTABLE 기본. IMMUTABLE이 "태그 → 코드"를 더 강하게 보장하지만, image.yml을
    workflow_dispatch로 같은 커밋에 재실행할 때 이미 올라간 SHA를 다시 push하다 실패한다.
    태그가 git SHA라 덮어쓸 이유가 없으므로 재실행 가능성을 택했다.
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
  default     = "flowticket-github-actions"
}

variable "github_oidc_subjects" {
  description = <<-EOT
    이 역할을 맡을 수 있는 GitHub 워크플로의 sub 조건(StringLike).

    ⚠️ 좁힐수록 안전하지만 실제 워크플로의 ref와 어긋나면 Image 파이프라인이 인증 실패한다.
    image.yml은 workflow_run(main)과 workflow_dispatch로 도는데 둘 다 ref가 main이다.
    import 후 plan에서 콘솔의 실제 값과 비교해 확정한다.
  EOT
  type        = list(string)
  default     = ["repo:gumwoo/first_come-first_served:ref:refs/heads/main"]

  validation {
    condition = alltrue([
      for s in var.github_oidc_subjects : can(regex("^repo:[^:]+/[^:]+:", s))
    ])
    error_message = "sub는 'repo:<owner>/<repo>:...' 형식이어야 한다(모든 저장소 허용 방지)."
  }
}

variable "github_oidc_thumbprints" {
  description = "콘솔 생성값과 다르면 import 후 plan에 diff로 뜬다 — 그때 실제 값으로 맞춘다."
  type        = list(string)
  default     = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

variable "tags" {
  type = map(string)
  default = {
    Project   = "flowticket"
    ManagedBy = "terraform"
    Layer     = "bootstrap"
  }
}
