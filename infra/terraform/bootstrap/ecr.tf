# ECR — ⚠️ Phase 2에서 콘솔로 이미 만든 저장소다. apply 전에 import해야 한다.
#   terraform import 'aws_ecr_repository.this["api"]' flowticket-api
#   terraform import 'aws_ecr_repository.this["web"]' flowticket-web
# 절차: README.md
#
# import 후 첫 plan은 콘솔 기본값과 아래 선언의 차이를 보여준다. 그 diff를 눈으로
# 확인하고 적용하는 것이 이 스택의 목적이다 — 지금까지 콘솔 설정이 코드에 없었다.

locals {
  ecr_repositories = {
    api = "flowticket-api"
    web = "flowticket-web"
  }
}

resource "aws_ecr_repository" "this" {
  for_each = local.ecr_repositories

  name = each.value

  # 기본값은 MUTABLE이다. IMMUTABLE이 "태그 → 코드"를 더 강하게 보장하지만,
  # image.yml은 workflow_dispatch로 같은 커밋을 재실행할 수 있어(실제로 ECR 저장소
  # 문제로 재실행한 적이 있다) 이미 올라간 SHA를 다시 push할 때 실패한다.
  # 태그가 git SHA라 덮어쓸 이유 자체가 없으므로, 재실행 가능성을 택했다.
  image_tag_mutability = var.ecr_image_tag_mutability

  image_scanning_configuration {
    scan_on_push = true
  }

  lifecycle {
    # 저장소를 지우면 그 안의 이미지가 전부 사라진다. CI가 다시 push할 수는 있지만
    # 과거 SHA로의 롤백 경로가 끊긴다.
    prevent_destroy = true
  }
}

# 태그가 SHA라 이미지가 계속 쌓인다. 최근 것만 남겨 저장 비용을 묶어 둔다.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 ${var.ecr_keep_last_images}개만 보관"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.ecr_keep_last_images
      }
      action = { type = "expire" }
    }]
  })
}
