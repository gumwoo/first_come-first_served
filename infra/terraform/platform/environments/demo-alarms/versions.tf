terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    # Slack 중계 Lambda 코드를 zip으로 묶는다(slack.tf).
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }

  # demo와 같은 state 버킷, 다른 키. 알람은 ALB가 생긴 뒤에만 만들 수 있어 별도 스택으로 둔다(main.tf 머리말).
  #   terraform init -backend-config=../demo/backend.hcl
  backend "s3" {
    key = "platform/demo-alarms.tfstate"
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "flowticket"
      Environment = "demo"
      ManagedBy   = "terraform"
    }
  }
}
