# GitHub Actions OIDC: 콘솔로 먼저 만든 것들이라 apply 전에 import한다. 명령·절차: README.md "1. 기존 자원 import".
# 코드로 가져오는 이유: CI 권한이 git diff로 리뷰된다. 콘솔에만 있으면 언제 누가 넓혔는지 알 수 없다.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # AWS가 이 공급자의 인증서를 자체 검증하므로 thumbprint는 사실상 형식값이다.
  # 값이 콘솔에서 만든 것과 다르면 import 후 plan에 diff로 뜬다. 그때 실제 값으로 맞춘다.
  thumbprint_list = var.github_oidc_thumbprints

  lifecycle {
    # 지우면 Image 워크플로가 즉시 역할을 맡지 못한다.
    prevent_destroy = true
  }
}

# 신뢰 정책: 누가 이 역할을 맡을 수 있는가.
data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # sub가 실제 워크플로의 ref와 어긋나면 Image 파이프라인이 인증에 실패한다. import 후 plan diff로 확인한다(README.md).
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = var.github_oidc_subjects
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name = var.github_actions_role_name
  # IAM role description은 한글·em dash를 거부한다(apply ValidationError). ASCII로만 쓴다.
  description        = "GitHub Actions OIDC - ECR push only"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json

  lifecycle {
    prevent_destroy = true
  }
}

# 권한: 무엇을 할 수 있는가. ECR push에 필요한 최소만 준다.
data "aws_iam_policy_document" "ecr_push" {
  # 로그인 토큰 발급은 리소스를 특정할 수 없다(API 자체가 계정 단위).
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # push는 우리 저장소 2개로만 제한한다. GetDownloadUrlForLayer(=pull)는 넣지 않는다.
  # CI는 push만 하고, 노드의 이미지 pull은 EKS 노드 역할이 담당한다.
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
    ]
    resources = [for r in aws_ecr_repository.this : r.arn]
  }
}

resource "aws_iam_role_policy" "ecr_push" {
  name   = "ecr-push"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.ecr_push.json
}
