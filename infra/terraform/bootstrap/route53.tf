# Hosted Zone은 Terraform이 소유하지 않는다.
#
# 도메인 등록 시 Route53 Registrar가 이미 만들었고, 재생성하면 NS가 바뀌어 레지스트라
# 설정을 다시 하고 DNS 전파까지 기다려야 한다. 이 스택에서 가장 되돌리기 번거로운 자원이라
# data로 읽기만 한다 — Terraform이 소유하지 않으면 실수로 destroy될 수도 없다.
# (terraform-design.md §1)

data "aws_route53_zone" "this" {
  name         = "${var.domain_name}."
  private_zone = false
}
