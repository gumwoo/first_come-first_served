# state 백엔드 자체를 만드는 스택 — 닭과 달걀이라 여기만 local state를 쓴다.
#
# 한 번 만들고 거의 손대지 않는다. bootstrap·platform이 이 버킷에 state를 둔다.
# state 파일에는 리소스 속성이 평문으로 들어가므로 버저닝·암호화·퍼블릭 차단이 필수다.

data "aws_caller_identity" "current" {}

locals {
  # 버킷 이름은 전역 유일해야 한다. 계정 ID를 붙여 결정적으로 만든다
  # (이 값은 apply 시점에 계산되며 저장소에 커밋되지 않는다 — backend.hcl은 gitignore 대상).
  bucket_name = "flowticket-tfstate-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "state" {
  bucket = local.bucket_name

  lifecycle {
    # 실수로 지우면 모든 스택의 state가 사라진다. destroy를 막아 둔다.
    prevent_destroy = true
  }

  tags = var.tags
}

# state가 깨졌을 때 되돌릴 유일한 수단이다. 반드시 켠다.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# state에는 엔드포인트·ARN 등 내부 구조가 그대로 들어간다. 공개 경로를 전부 막는다.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 버저닝을 켜면 과거 버전이 계속 쌓인다. apply/destroy를 반복하는 프로젝트라
# 오래된 버전은 정리해 저장 비용이 늘지 않게 한다(복구 여지는 남긴다).
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  # 두 리소스 모두 버킷만 참조하므로 Terraform 그래프상 **서로 순서 제약이 없다** —
  # 버저닝보다 먼저 적용될 수 있다. 그러면 비활성 버킷에 "비현행 버전 만료" 규칙이 걸리는
  # 의미상 어긋난 상태가 되고, 실행 순서가 매번 달라져 재현되지 않는 실패가 될 수 있다.
  # (AWS가 이를 거부하는지는 확인하지 않았다. 다만 순서를 우연에 맡길 이유가 없다.)
  depends_on = [aws_s3_bucket_versioning.state]

  rule {
    id     = "expire-old-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ---------------------------------------------------------------------------
# 잠금
# ---------------------------------------------------------------------------
# Terraform 1.10+ 는 S3 네이티브 잠금(use_lockfile)을 지원해 이 테이블이 필요 없다.
# 현재 CI가 1.9.8을 쓰므로 DynamoDB 잠금을 둔다. 버전을 올리면 var.create_lock_table을
# false로 두고 backend에서 use_lockfile을 쓰면 된다.
resource "aws_dynamodb_table" "lock" {
  count = var.create_lock_table ? 1 : 0

  name         = "flowticket-tfstate-lock"
  billing_mode = "PAY_PER_REQUEST" # 잠금 트래픽은 극소량이라 온디맨드가 싸다
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  lifecycle {
    prevent_destroy = true
  }

  tags = var.tags
}
