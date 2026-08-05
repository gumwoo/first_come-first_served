terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # backend를 두지 않는다 — 이 스택이 만드는 버킷에 자기 state를 둘 수는 없다.
  # local state(terraform.tfstate)가 남으며 .gitignore 대상이다.
  # 유실돼도 버킷은 그대로 있고, 필요하면 import로 다시 붙일 수 있다.
}

provider "aws" {
  region = var.region
}
