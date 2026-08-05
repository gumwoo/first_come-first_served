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

# DNS 검증 레코드.
#
# for_each 키는 **도메인 이름**으로 잡는다. 이 값은 설정(domain_name·SAN)에서 오므로
# apply 전에도 알 수 있다. 레코드 이름(resource_record_name)을 키로 쓰면
# "for_each will be known only after apply"로 실패한다 — 그 값은 인증서가 만들어진
# 뒤에야 정해지기 때문이다. (초안이 그렇게 썼다가 import 단계에서 드러났다.)
#
# 루트와 와일드카드는 **검증 레코드 이름·값이 같아** 결과적으로 같은 레코드를 두 리소스가
# 쓴다. 내용이 동일하고 allow_overwrite로 UPSERT라 실질 결과는 레코드 하나다.
# 이것이 AWS 공급자 문서가 안내하는 표준 형태다.
#
# ⚠️ 이 문제는 `terraform validate`로 잡히지 않는다 — 값이 apply 이후에 정해지기 때문이다.
locals {
  # for_each 키는 **설정에서 오는 정적 값**이어야 한다. 인증서가 만들 검증 항목의
  # 도메인 집합은 우리가 선언한 것(루트 + 와일드카드)과 같으므로 여기서 직접 만든다.
  cert_domain_names = toset([var.domain_name, "*.${var.domain_name}"])

  # 값은 apply 이후에 정해져도 된다 — 키만 정적이면 된다.
  validation_by_domain = {
    for opt in aws_acm_certificate.this.domain_validation_options :
    opt.domain_name => opt
  }
}

resource "aws_route53_record" "validation" {
  for_each = local.cert_domain_names

  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.validation_by_domain[each.key].resource_record_name
  type    = local.validation_by_domain[each.key].resource_record_type
  records = [local.validation_by_domain[each.key].resource_record_value]
  ttl     = 60

  # 같은 이름의 레코드가 이미 있으면(재발급·와일드카드 중복) 덮어쓴다.
  allow_overwrite = true
}

# 검증이 끝날 때까지 기다린다. 이걸 두지 않으면 아직 PENDING인 인증서 ARN이
# platform으로 넘어가 ALB 생성이 실패한다.
resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.validation : r.fqdn]
}
