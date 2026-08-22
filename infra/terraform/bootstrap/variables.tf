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

variable "ecr_untagged_expire_days" {
  description = <<-EOT
    태그 없는 이미지를 며칠 뒤 정리할지. 같은 SHA로 재푸시하면(workflow_dispatch 재실행)
    이전 이미지가 태그를 잃고 남는다 — 그것들은 태그로 참조할 수 없어 롤백에 쓸 수 없다.
    0으로 두면 이 규칙을 끈다.
  EOT
  type        = number
  default     = 1
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

    **main 브랜치로 한정한다.** 이전에는 `repo:<owner>/<repo>:*` 였는데, 그러면 이 저장소의
    모든 브랜치·태그·PR이 역할을 맡아 ECR에 push할 수 있다.

    좁히기 전에 **CloudTrail의 AssumeRoleWithWebIdentity 이벤트로 실제 sub를 확인**했다
    (14건 전부 `...:ref:refs/heads/main`). image.yml은 workflow_run(main)과 workflow_dispatch로
    도는데 둘 다 같은 값이었다 — 추측이 아니라 기록으로 확인한 뒤 좁혔다.

    ⚠️ 나중에 다른 브랜치나 태그에서 이미지를 내보내야 하면 여기에 조건을 **추가**해야 한다.
    빠뜨리면 워크플로가 인증 실패하는데, 증상이 "권한 없음"이라 원인을 찾기 번거롭다.
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
