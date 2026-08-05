terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # state는 S3에 둔다. apply/destroy를 반복하므로 state 유실이 치명적이다.
  # 버킷은 state-bootstrap에서 먼저 만들고, 아래 값을 채운 뒤 `terraform init`.
  #
  # backend "s3" {
  #   bucket = "flowticket-tfstate-<suffix>"
  #   key    = "platform/demo.tfstate"
  #   region = "ap-northeast-2"
  #   # 잠금: Terraform 버전이 지원하면 use_lockfile, 아니면 dynamodb_table.
  #   # use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}
