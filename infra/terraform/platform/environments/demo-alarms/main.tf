# ALB 계층 오류 알람 — 앱(Spring) 지표가 보지 못하는 오류를 ALB 지표로 본다.
#
# 왜 필요한가: PrometheusRule의 서버 오류 알람은 http_server_requests{outcome="SERVER_ERROR"}, 즉 요청이 Spring까지
# 와서 5xx로 끝난 경우만 센다. 롤링 배포 중 502(TS-035)는 ALB가 대상에 닿지 못해 스스로 만든 오류라 앱 5xx가 0이었고,
# 같은 장애가 다시 나도 그 알람은 울리지 않는다. 그래서 ALB가 내는 CloudWatch 지표로 따로 본다.
#   - HTTPCode_ELB_5XX_Count   : ALB가 직접 만든 5xx(대상 연결 실패·타임아웃·정상 대상 없음)
#   - TargetConnectionErrorCount: ALB가 대상과 연결을 맺지 못한 횟수(종료 중인 파드로 보낸 요청 등)
#   - HealthyHostCount(대상 그룹별): 정상 대상이 0이면 그 서비스로 가는 요청은 전부 503
#
# 왜 별도 스택인가: ALB는 Terraform이 아니라 AWS Load Balancer Controller가 Ingress를 보고 만든다. demo 스택을 apply하는
# 시점에는 ALB가 없어 알람 차원(LoadBalancer=app/<이름>/<id>)을 알 수 없다. 그래서 ALB가 생긴 뒤 이 스택을 따로 적용하고
# (scripts/alb-alarms.sh apply), 철거 때는 Ingress를 지우기 전에 먼저 지운다(scripts/tear-down.sh).
# ALB를 data "aws_lb"로 찾지 않고 변수로 받는 이유: data 소스는 destroy 때도 다시 읽혀, ALB가 이미 없으면 철거가 실패한다.

resource "aws_sns_topic" "alb_alarms" {
  name = "flowticket-alb-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alb_alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  actions = [aws_sns_topic.alb_alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "elb_5xx" {
  alarm_name          = "flowticket-alb-elb-5xx"
  alarm_description   = "ALB가 직접 만든 5xx(대상에 닿지 못함)가 1분에 ${var.elb_5xx_threshold}건 이상, 2분 연속. 앱 5xx 알람으로는 보이지 않는다. 대응: ${var.runbook_url}"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 2
  threshold           = var.elb_5xx_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  # 오류가 없으면 데이터 점이 없다(0을 보내지 않는다) — 없음은 정상으로 본다.
  treat_missing_data = "notBreaching"
  alarm_actions      = local.actions
  ok_actions         = local.actions
}

resource "aws_cloudwatch_metric_alarm" "target_connection_errors" {
  alarm_name          = "flowticket-alb-target-connection-errors"
  alarm_description   = "ALB가 대상(파드)과 연결을 맺지 못함 — 종료 중인 파드로 보낸 요청(TS-035) 등. 대응: ${var.runbook_url}"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetConnectionErrorCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
}

resource "aws_cloudwatch_metric_alarm" "no_healthy_targets" {
  for_each = toset(var.target_group_arn_suffixes)

  alarm_name          = "flowticket-alb-no-healthy-${replace(split("/", each.value)[1], "k8s-", "")}"
  alarm_description   = "대상 그룹 ${each.value}의 정상 대상이 0 — 이 서비스로 가는 요청은 ALB가 503으로 끝낸다. 대응: ${var.runbook_url}"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = each.value }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  # ALB는 이 지표를 매분 보낸다. 점이 끊기면 ALB나 대상 그룹 자체가 사라진 것이라 경보로 본다.
  treat_missing_data = "breaching"
  alarm_actions      = local.actions
  ok_actions         = local.actions
}
