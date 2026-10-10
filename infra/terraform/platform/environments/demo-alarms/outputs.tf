output "sns_topic_arn" {
  description = "ALB 알람이 보내지는 SNS 토픽. 다른 수신처(Slack 등)는 이 토픽에 구독을 붙인다."
  value       = aws_sns_topic.alb_alarms.arn
}

output "alarm_names" {
  value = concat(
    [aws_cloudwatch_metric_alarm.elb_5xx.alarm_name, aws_cloudwatch_metric_alarm.target_connection_errors.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.no_healthy_targets : a.alarm_name],
  )
}

output "slack_notify_function" {
  description = "ALB 알람 → Slack 중계 Lambda(slack_enabled=false면 null). 발화 확인은 이 함수의 로그 그룹을 본다."
  value       = var.slack_enabled ? aws_lambda_function.slack_notify[0].function_name : null
}
