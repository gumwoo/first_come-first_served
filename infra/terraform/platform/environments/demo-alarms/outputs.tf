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
