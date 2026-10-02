# 클러스터 밖 부하 발생기(loadtest-100k-plan §4).
#
# 왜 클러스터 밖인가: 발생기가 클러스터 안 Job으로 돌면 측정 대상과 노드를 나눠 쓴다. TS-034에서 k6가 앱과 같은
# 노드에서 최대 0.59 core를 썼고, 그 측정은 무효가 됐다(§3.1 "자원 경합"). 그래서 별도 EC2에서 ALB 공인 경로로 건다.
#
# 접속은 SSM으로만 한다. 인바운드 규칙과 SSH 키가 없다 — 공개 저장소의 인프라에 접속 경로를 늘리지 않는다.
# 결과는 S3 버킷에 올리고 운영자가 받아 간다(scripts/loadtest/loadgen.sh pull). 버킷은 철거 때 함께 지워지므로
# run마다 받아 두는 것이 전제다(§7 "run이 끝날 때마다 즉시 저장한다").
#
# instance_count = 0(기본)이면 아무것도 만들지 않는다. 평소 apply에는 비용이 0이다.

locals {
  enabled = var.instance_count > 0
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# Amazon Linux 2023. SSM 에이전트가 기본으로 들어 있다.
data "aws_ssm_parameter" "al2023" {
  count = local.enabled ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_s3_bucket" "results" {
  count = local.enabled ? 1 : 0

  # 버킷 이름은 전역 유일이어야 해서 계정 ID를 붙인다. 이름은 state에만 남고 커밋되지 않는다.
  bucket = "${var.name}-loadgen-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.name}"

  # 철거가 버킷 안 객체 때문에 막히지 않게 한다. 결과는 run마다 운영자가 받아 두므로 여기서 지워도 된다.
  force_destroy = true

  tags = merge(var.tags, { Role = "loadgen" })
}

resource "aws_s3_bucket_public_access_block" "results" {
  count  = local.enabled ? 1 : 0
  bucket = aws_s3_bucket.results[0].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_security_group" "loadgen" {
  count = local.enabled ? 1 : 0

  name        = "${var.name}-loadgen"
  description = "load generator: egress only, no inbound (SSM)"
  vpc_id      = var.vpc_id

  egress {
    description = "ALB, GitHub, S3, SSM"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${var.name}-loadgen", Role = "loadgen" })
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "loadgen" {
  count              = local.enabled ? 1 : 0
  name               = "${var.name}-loadgen"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ssm" {
  count      = local.enabled ? 1 : 0
  role       = aws_iam_role.loadgen[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 결과 버킷에만 쓰고 읽는다.
data "aws_iam_policy_document" "results" {
  count = local.enabled ? 1 : 0

  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.results[0].arn]
  }
  statement {
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.results[0].arn}/*"]
  }
}

resource "aws_iam_role_policy" "results" {
  count  = local.enabled ? 1 : 0
  name   = "results-bucket"
  role   = aws_iam_role.loadgen[0].id
  policy = data.aws_iam_policy_document.results[0].json
}

resource "aws_iam_instance_profile" "loadgen" {
  count = local.enabled ? 1 : 0
  name  = "${var.name}-loadgen"
  role  = aws_iam_role.loadgen[0].name
  tags  = var.tags
}

resource "aws_instance" "loadgen" {
  count = var.instance_count

  ami                         = data.aws_ssm_parameter.al2023[0].value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_ids[count.index % length(var.subnet_ids)]
  vpc_security_group_ids      = [aws_security_group.loadgen[0].id]
  iam_instance_profile        = aws_iam_instance_profile.loadgen[0].name
  associate_public_ip_address = true # NAT 없이 ALB 공인 주소·GitHub에 닿는다

  metadata_options {
    http_tokens = "required" # IMDSv2만
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    k6_version = var.k6_version
    repo_url   = var.repo_url
    bucket     = aws_s3_bucket.results[0].bucket
  })

  tags = merge(var.tags, { Name = "${var.name}-loadgen-${count.index + 1}", Role = "loadgen" })

  # AMI는 SSM 파라미터에서 "최신"을 받는다. 측정 세션 중간에 다시 apply했을 때 새 AMI가 나와 있으면
  # 발생기가 교체되고, 아직 회수하지 않은 run 출력이 함께 사라진다. 세션 안에서는 AMI 변경을 무시한다.
  lifecycle {
    ignore_changes = [ami]
  }
}
