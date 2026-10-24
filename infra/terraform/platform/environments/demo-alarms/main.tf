# ALB 계층 오류 알람. 앱 5xx 알람(outcome="SERVER_ERROR")은 ALB가 대상에 닿지 못해 스스로 만든 오류
# (롤링 배포 502, TS-035)를 보지 못해 ALB CloudWatch 지표로 따로 본다.
# ALB는 LBC가 Ingress를 보고 만들어 demo apply 시점에 없으므로 별도 스택이다(scripts/alb-alarms.sh apply).
# data "aws_lb"로 찾지 않고 변수로 받는다. data 소스는 destroy 때도 읽혀 ALB가 없으면 철거가 실패한다(terraform-design §1).

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
  alarm_name        = "flowticket-alb-elb-5xx"
  alarm_description = "ALB가 직접 만든 5xx(대상에 닿지 못함)가 1분에 ${var.elb_5xx_threshold}건 이상. 앱 5xx 알람으로는 보이지 않는다. 대응: ${var.runbook_url}"
  namespace         = "AWS/ApplicationELB"
  metric_name       = "HTTPCode_ELB_5XX_Count"
  dimensions        = { LoadBalancer = var.alb_arn_suffix }
  statistic         = "Sum"
  period            = 60
  # 1분 하나로 판정한다. TS-035의 502는 십수 초 안에 끝나는 순간 장애라 2분 연속을 요구하면 놓친다.
  # 분 경계로 나뉘어 임계 아래가 되면 아래 TargetConnectionError 알람(1건 이상)이 잡는다.
  evaluation_periods  = 1
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
  # 키 = 서비스 이름(api·web 등), 값 = 대상 그룹 ARN 접미사. 대상 그룹 이름은 LBC가 해시로 만들어 어느 서비스인지 알 수 없다.
  # 대상 그룹이 다시 만들어지면(Service·헬스체크 변경) 옛 대상 그룹 알람은 데이터가 끊겨 울린다 — alb-alarms.sh apply를 다시 한다.
  for_each = var.target_groups

  alarm_name          = "flowticket-alb-no-healthy-${each.key}"
  alarm_description   = "${each.key} 대상 그룹(${each.value})의 정상 대상이 0 — 이 서비스로 가는 요청은 ALB가 503으로 끝낸다. 대응: ${var.runbook_url}"
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
