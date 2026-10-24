# ECR: 콘솔로 먼저 만든 저장소라 apply 전에 import해야 한다. 명령·절차: README.md "1. 기존 자원 import".
# import 후 첫 plan의 diff(콘솔 기본값 vs 아래 선언)를 확인하고 적용하는 것이 이 스택의 목적이다.

locals {
  ecr_repositories = {
    api = "flowticket-api"
    web = "flowticket-web"
  }
}

resource "aws_ecr_repository" "this" {
  for_each = local.ecr_repositories

  name = each.value

  # MUTABLE로 둔다. image.yml을 workflow_dispatch로 재실행하면 같은 SHA 태그를 다시 push하는데
  # IMMUTABLE이면 실패한다. 태그가 git SHA라 서로 다른 커밋이 같은 태그를 쓰지는 않는다.
  image_tag_mutability = var.ecr_image_tag_mutability

  image_scanning_configuration {
    scan_on_push = true
  }

  lifecycle {
    # 지우면 이미지가 전부 사라져 과거 SHA로의 롤백 경로가 끊긴다.
    prevent_destroy = true
  }
}

# 태그가 SHA라 이미지가 계속 쌓인다. 최근 것만 남겨 저장 비용을 묶어 둔다.
resource "aws_ecr_lifecycle_policy" "this" {
  # for_each를 aws_ecr_repository.this로 잡으면 키가 "apply 이후에나 아는 값"이 되어
  # import·plan이 막힌다. 키는 정적인 local 맵에서 가져오고, 저장소 참조만 남긴다.
  for_each = local.ecr_repositories

  repository = aws_ecr_repository.this[each.key].name

  # 규칙 순서가 의미를 만든다. untagged(같은 SHA 재push 때 생기고 롤백에 못 쓴다)를 먼저 걷어내지 않으면
  # 보관 한도(${var.ecr_keep_last_images}개)의 자리를 차지해 롤백 가능한 태그 이미지가 그만큼 줄어든다.
  policy = jsonencode({
    rules = concat(
      var.ecr_untagged_expire_days > 0 ? [{
        rulePriority = 1
        description  = "태그 없는 이미지는 ${var.ecr_untagged_expire_days}일 뒤 정리"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.ecr_untagged_expire_days
        }
        action = { type = "expire" }
      }] : [],
      [{
        # 우선순위는 위 규칙보다 뒤여야 한다(작은 수가 먼저 평가된다).
        rulePriority = 2
        description  = "최근 ${var.ecr_keep_last_images}개만 보관"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_keep_last_images
        }
        action = { type = "expire" }
      }]
    )
  })
}
