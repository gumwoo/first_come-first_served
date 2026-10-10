# ALB 알람을 Slack으로 — SNS 토픽에 Lambda 구독을 붙여, Prometheus 알림(Alertmanager)과 같은 웹훅으로 보낸다.
#
# 왜 Lambda인가(AWS Chatbot 대신): 웹훅 하나 = 채널 하나(kube-prometheus-stack.values.yaml)라 이미 있는 비밀을 그대로 쓰면
# 수신처·비밀이 하나로 유지되고, 전부 코드라 alb-alarms.sh apply/destroy에 함께 묶인다. Chatbot은 콘솔에서 Slack
# 워크스페이스 OAuth 승인이 한 번 필요하고 웹훅과 별개의 연동이 하나 더 생긴다.
#
# 웹훅 URL을 Lambda 환경변수로 넣지 않는다 — 그러면 Terraform state에 평문으로 남는다. 파라미터 이름만 넘기고 실행 중에 읽는다.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  slack_fn_name        = "flowticket-alb-alarms-slack"
  slack_webhook_ps_arn = "arn:${data.aws_partition.current.partition}:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${var.slack_webhook_param}"
}

data "archive_file" "slack_notify" {
  count       = var.slack_enabled ? 1 : 0
  type        = "zip"
  source_file = "${path.module}/lambda/slack_notify.py"
  output_path = "${path.module}/.terraform/build/slack_notify.zip" # .terraform/은 gitignore 대상
}

data "aws_iam_policy_document" "slack_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "slack_notify" {
  count              = var.slack_enabled ? 1 : 0
  name               = local.slack_fn_name
  assume_role_policy = data.aws_iam_policy_document.slack_assume.json
}

# 권한은 웹훅 파라미터 하나와 자기 로그 그룹으로 좁힌다(ESO 정책과 같은 방식 — modules/eks/irsa.tf).
data "aws_iam_policy_document" "slack_notify" {
  statement {
    sid       = "ReadWebhookParameter"
    actions   = ["ssm:GetParameter"]
    resources = [local.slack_webhook_ps_arn]
  }
  # SecureString은 기본 키(alias/aws/ssm)로 암호화돼 있어 복호화 권한이 필요하다. SSM을 거친 호출로만 한정한다.
  statement {
    sid       = "DecryptViaSsm"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }
  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["arn:${data.aws_partition.current.partition}:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/lambda/${local.slack_fn_name}:*"]
  }
}

resource "aws_iam_role_policy" "slack_notify" {
  count  = var.slack_enabled ? 1 : 0
  role   = aws_iam_role.slack_notify[0].id
  policy = data.aws_iam_policy_document.slack_notify.json
}

# 로그 그룹을 먼저 만든다. Lambda가 자동으로 만들게 두면 보존이 "만료 없음"이고 destroy로도 지워지지 않는다
# (platform README의 컨트롤플레인 로그 그룹과 같은 이유).
resource "aws_cloudwatch_log_group" "slack_notify" {
  count             = var.slack_enabled ? 1 : 0
  name              = "/aws/lambda/${local.slack_fn_name}"
  retention_in_days = 7
}

resource "aws_lambda_function" "slack_notify" {
  count            = var.slack_enabled ? 1 : 0
  function_name    = local.slack_fn_name
  role             = aws_iam_role.slack_notify[0].arn
  runtime          = "python3.12"
  handler          = "slack_notify.handler"
  filename         = data.archive_file.slack_notify[0].output_path
  source_code_hash = data.archive_file.slack_notify[0].output_base64sha256
  timeout          = 10
  environment {
    variables = { WEBHOOK_PARAM = var.slack_webhook_param }
  }
  depends_on = [aws_cloudwatch_log_group.slack_notify, aws_iam_role_policy.slack_notify]
}

resource "aws_lambda_permission" "slack_from_sns" {
  count         = var.slack_enabled ? 1 : 0
  statement_id  = "AllowFromAlbAlarmsTopic"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.slack_notify[0].function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.alb_alarms.arn
}

resource "aws_sns_topic_subscription" "slack" {
  count      = var.slack_enabled ? 1 : 0
  topic_arn  = aws_sns_topic.alb_alarms.arn
  protocol   = "lambda"
  endpoint   = aws_lambda_function.slack_notify[0].arn
  depends_on = [aws_lambda_permission.slack_from_sns]
}
