terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # 버킷 이름에 계정 ID가 들어가므로 값을 커밋하지 않는다.
  #   terraform init -backend-config=backend.hcl
  # backend.hcl 은 state-bootstrap 의 backend_hcl 출력을 저장한 것이며 .gitignore 대상이다.
  backend "s3" {
    key = "bootstrap/terraform.tfstate"
  }
}

provider "aws" {
  # ⚠️ ACM 인증서는 ALB와 같은 리전이어야 한다. us-east-1은 CloudFront용이다.
  region = var.region

  default_tags {
    tags = var.tags
  }
}
