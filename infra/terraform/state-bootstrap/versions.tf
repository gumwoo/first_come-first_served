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
  #
  # ⚠️ 이 파일을 별도로 백업한다(암호화된 개인 저장소). 유실돼도 AWS 자원은 지워지지
  #    않지만, 버킷·버저닝·암호화·퍼블릭차단·라이프사이클·DynamoDB를 **각각 정확한
  #    주소로 import**해야 복구된다. "다시 만들면 된다"가 아니라 복구 비용이 큰 쪽이다.
}

provider "aws" {
  region = var.region
}
