"""ALB 알람(SNS) → Slack 중계.

CloudWatch 알람은 SNS로만 나간다. Prometheus 알림이 쓰는 같은 Slack 웹훅으로 보내 수신처를 한 곳에 모은다.
웹훅 URL은 비밀이라 환경변수(= Terraform state에 평문)로 받지 않고, 실행 중에 SSM SecureString에서 읽는다.
URL은 로그에 남기지 않는다.
"""
import json
import os
import urllib.request

import boto3

_ssm = boto3.client("ssm")
_webhook = None  # 같은 실행 환경(warm)에서는 다시 읽지 않는다

_MARK = {"ALARM": ":rotating_light:", "OK": ":white_check_mark:", "INSUFFICIENT_DATA": ":grey_question:"}


def _webhook_url():
    global _webhook
    if _webhook is None:
        resp = _ssm.get_parameter(Name=os.environ["WEBHOOK_PARAM"], WithDecryption=True)
        _webhook = resp["Parameter"]["Value"]
    return _webhook


def _text(sns):
    """CloudWatch 알람 메시지(JSON)를 Alertmanager 알림과 비슷한 모양으로. JSON이 아니면 원문 그대로."""
    try:
        m = json.loads(sns["Message"])
        new = m["NewStateValue"]
    except (ValueError, TypeError, KeyError):
        return "[ALB] {}\n{}".format(sns.get("Subject") or "", sns.get("Message", ""))
    return "{} [{}] ALB: {} ({} → {})\n*{}*\n{}".format(
        _MARK.get(new, ""), new, m.get("AlarmName", ""), m.get("OldStateValue", "?"), new,
        m.get("NewStateReason", ""), m.get("AlarmDescription", ""),
    )


def handler(event, context):
    for rec in event.get("Records", []):
        body = json.dumps({"text": _text(rec["Sns"])}).encode("utf-8")
        req = urllib.request.Request(_webhook_url(), data=body, headers={"Content-Type": "application/json"})
        # 실패하면 예외로 끝낸다 — SNS→Lambda 비동기 호출은 Lambda가 재시도한다(기본 2회).
        with urllib.request.urlopen(req, timeout=5) as resp:
            resp.read()
