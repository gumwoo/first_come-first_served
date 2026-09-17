# ACM 인증서 — 이 스택에서 유일하게 "새로 만드는" 자원이다.
#
# 루트와 와일드카드를 둘 다 넣는다. *.flow-ticket.com 은 flow-ticket.com 자신을 포함하지
# 않기 때문이다. 와일드카드는 1레벨만 커버하므로 api. · argocd. · grafana. 는 되고
# a.b. 는 안 된다 — 계획된 서브도메인이 전부 1레벨이라 문제없다.

resource "aws_acm_certificate" "this" {
  domain_name               = var.domain_name
  subject_alternative_names = ["*.${var.domain_name}"]
  validation_method         = "DNS"

  lifecycle {
    # 인증서를 교체할 때 옛 것을 먼저 지우면 ALB가 잠시 인증서를 잃는다.
    create_before_destroy = true
  }
}

# DNS 검증 레코드 — 루트와 와일드카드가 같은 CNAME을 쓰므로 리소스 1개로 둔다.
# 도메인별로 만들면 레코드 하나를 주소 둘이 소유한다(bootstrap/README.md "검증 레코드").
locals {
  # 검증 항목 중 루트 도메인 것 하나만 쓴다(와일드카드가 같은 레코드를 공유하므로).
  apex_validation = one([
    for opt in aws_acm_certificate.this.domain_validation_options :
    opt if opt.domain_name == var.domain_name
  ])
}

resource "aws_route53_record" "validation" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.apex_validation.resource_record_name
  type    = local.apex_validation.resource_record_type
  records = [local.apex_validation.resource_record_value]
  ttl     = 60

  # 재발급 시 같은 이름의 레코드를 덮어쓴다.
  allow_overwrite = true
}

# 검증이 끝날 때까지 기다린다. 이걸 두지 않으면 아직 PENDING인 인증서 ARN이
# platform으로 넘어가 ALB 생성이 실패한다.
resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [aws_route53_record.validation.fqdn]

  lifecycle {
    # "검증 레코드는 하나"라는 전제를 코드가 직접 강제한다.
    # SAN 구성이 바뀌어 검증 레코드가 여러 개가 되면 여기서 멈춘다 —
    # 조용히 일부 도메인의 검증이 누락되는 것보다 낫다.
    precondition {
      condition = length(distinct([
        for opt in aws_acm_certificate.this.domain_validation_options :
        opt.resource_record_name
      ])) == 1
      error_message = "검증 레코드가 1개라는 전제가 깨졌다. 도메인별로 레코드를 만들도록 acm.tf를 고쳐라."
    }
  }
}
