# bootstrap(영속 계층)의 출력을 읽는다.
#
# 두 스택을 나눈 이유는 `platform destroy`가 도메인·인증서·이미지까지 지우지 않게 하기
# 위해서다(ADR-012 §9). 그래서 platform은 bootstrap을 **읽기만** 한다.
#
# ⚠️ 민감값은 이 경로로 넘기지 않는다 — state 파일은 평문이다. DB 자격증명 등은
# Secrets Manager에 두고 ARN만 전달한다(modules/rds).

data "terraform_remote_state" "bootstrap" {
  backend = "s3"

  config = {
    bucket = var.state_bucket
    key    = "bootstrap/terraform.tfstate"
    region = var.region
  }
}

locals {
  # ALB Ingress가 붙일 인증서와 Alias 레코드를 만들 Zone.
  # 아직 platform이 Ingress를 만들지 않으므로(ArgoCD 몫 — ADR-009) 지금은 출력으로만 노출한다.
  certificate_arn = data.terraform_remote_state.bootstrap.outputs.certificate_arn
  hosted_zone_id  = data.terraform_remote_state.bootstrap.outputs.hosted_zone_id
  domain_name     = data.terraform_remote_state.bootstrap.outputs.domain_name
}
