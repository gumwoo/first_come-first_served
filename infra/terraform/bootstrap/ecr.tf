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

  # 기본값은 MUTABLE이다. image.yml을 workflow_dispatch로 재실행하면 같은 SHA 태그를
  # 다시 push하는데(실제로 ECR 저장소 리전 문제로 재실행한 적이 있다) IMMUTABLE이면 실패한다.
  # 즉 재실행 시 **덮어쓰기가 실제로 일어난다** — 다만 서로 다른 커밋이 같은 태그를 쓰는
  # 일은 없으므로(태그 = git SHA) 태그 재사용 위험은 제한적이다. 재실행 가능성을 택했다.
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
