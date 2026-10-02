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
{
  echo "[scheduled] start $(date -Is) session=$SESSION"
  bash scripts/tear-down.sh
  echo "[scheduled] tear-down exit=$?"
  bash scripts/tear-down.sh --audit-only
  echo "[scheduled] audit exit=$?"
  echo "[scheduled] end $(date -Is)"
} > "$LOG" 2>&1
echo "로그: $LOG"
