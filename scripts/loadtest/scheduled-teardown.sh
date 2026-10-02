#!/usr/bin/env bash
# 예약 작업(schedule-teardown.ps1)이 실행하는 본체. 철거 → 감사를 순서대로 돌리고 둘 다 로그에 남긴다.
# 철거가 실패해도 감사는 돈다. 철거 성공은 종료 코드가 아니라 감사 출력(잔여 0)으로 판정한다(TS-038).
#
#   bash scripts/loadtest/scheduled-teardown.sh <session-id>
set -uo pipefail

SESSION="${1:?session-id가 필요하다}"
case "$SESSION" in [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9]) ;; *) echo "session-id 형식: YYYYMMDD-HHMM" >&2; exit 2 ;; esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

mkdir -p "artifacts/loadtest/$SESSION"
LOG="artifacts/loadtest/$SESSION/scheduled-teardown-$(date +%Y%m%d-%H%M%S).log"
TEARDOWN_RC=0 AUDIT_RC=0
{
  echo "[scheduled] start $(date -Is) session=$SESSION"
  bash scripts/tear-down.sh; TEARDOWN_RC=$?
  echo "[scheduled] tear-down exit=$TEARDOWN_RC"
  bash scripts/tear-down.sh --audit-only; AUDIT_RC=$?
  echo "[scheduled] audit exit=$AUDIT_RC"
  echo "[scheduled] end $(date -Is)"
} > "$LOG" 2>&1
echo "로그: $LOG"
# 철거와 감사 중 하나라도 실패하면 0이 아닌 값으로 끝낸다. 작업 스케줄러의 마지막 결과(LastTaskResult)가
# 철거 실패를 가리지 않게 한다. 감사는 조회 실패도 실패로 센다(tear-down.sh audit).
[ "$TEARDOWN_RC" -eq 0 ] && [ "$AUDIT_RC" -eq 0 ] && exit 0
exit 1
