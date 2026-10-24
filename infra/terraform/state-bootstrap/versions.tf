terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # backend를 두지 않는다. 이 스택이 만드는 버킷에 자기 state를 둘 수는 없다.
  # local state(terraform.tfstate)는 .gitignore 대상이며 반드시 따로 백업한다(README.md).
}

provider "aws" {
  region = var.region
}
