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

locals {
  # 루트와 와일드카드는 **검증 레코드 이름·값이 같다.** ACM은 도메인마다 항목을 주므로
  # 같은 레코드가 두 번 나온다.
  #
  # 주의: 레코드 이름을 그냥 for 키로 쓰면 중복이 합쳐지는 게 아니라
  # "Duplicate object key" 오류가 난다. 끝의 `...`(그룹핑)이 있어야 같은 키의 값들이
  # 리스트로 묶인다. 값이 동일하므로 [0]만 쓰면 레코드는 하나만 생긴다.
  #
  # 도메인 이름을 키로 잡는 방법도 있지만, 그러면 같은 Route53 레코드를 두 리소스가
  # 각각 관리하게 되어 서로 덮어쓰는 상태가 된다.
  #
  # ⚠️ domain_validation_options는 apply 이후에 정해지는 값이라 이 결함은
  #    `terraform validate`로 잡히지 않는다 — plan/apply에서야 드러난다.
  acm_validation = {
    for opt in aws_acm_certificate.this.domain_validation_options :
    opt.resource_record_name => {
      type   = opt.resource_record_type
      record = opt.resource_record_value
    }...
  }
}

resource "aws_route53_record" "validation" {
  for_each = local.acm_validation

  zone_id = data.aws_route53_zone.this.zone_id
  name    = each.key
  type    = each.value[0].type
  records = [each.value[0].record]
  ttl     = 60

  # 같은 이름의 레코드가 이미 있으면(재발급 등) 덮어쓴다.
  allow_overwrite = true
}

# 검증이 끝날 때까지 기다린다. 이걸 두지 않으면 아직 PENDING인 인증서 ARN이
# platform으로 넘어가 ALB 생성이 실패한다.
resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.validation : r.fqdn]
}
