# state 백엔드 자체를 만드는 스택: 닭과 달걀이라 여기만 local state를 쓴다.
# bootstrap·platform이 이 버킷에 state를 둔다. state에는 리소스 속성이 평문으로 들어가므로
# 버저닝·암호화·퍼블릭 차단이 필수다.

data "aws_caller_identity" "current" {}

locals {
  # 전역 유일하도록 계정 ID를 붙인다. 이 값은 커밋되지 않는다(backend.hcl은 gitignore 대상).
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

  # 버저닝과 서로 순서 제약이 없어, 버저닝이 꺼진 버킷에 "비현행 버전 만료" 규칙이 먼저 걸릴 수 있다.
  # AWS가 이를 거부하는지는 확인하지 않았지만 순서를 우연에 맡기지 않는다.
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
# CI가 Terraform 1.9.8이라 S3 네이티브 잠금(use_lockfile, 1.10+) 대신 DynamoDB 잠금을 둔다. 전환법은 README.md.
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
