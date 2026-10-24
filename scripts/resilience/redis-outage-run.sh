#!/usr/bin/env bash
# Redis 장애 두 가지(중지 = 연결 거부, 일시정지 = 응답 없음)를 차례로 넣고, 장애 중 api 인스턴스별 응답을 잰다(ADR-030).
#   bash scripts/resilience/redis-outage-run.sh <결과.jsonl> <대상 목록> <tokens.json 디렉터리> <event> <seat>
#   예: bash scripts/resilience/redis-outage-run.sh out.jsonl a=http://ready-a:8080,b=http://ready-b:8080 /tmp/tok 173 17693
# 실험실(infra/triage-lab)의 Redis 컨테이너(ft-triage-redis-1)를 멈췄다가 되돌린다. 대상 api 컨테이너는 같은 네트워크에 따로 띄운다.
set -u
out=$1 targets=$2 tokdir=$3 event=$4 seat=$5
REDIS=ft-triage-redis-1 NET=ft-triage_default
here=$(cd "$(dirname "$0")" && pwd); here=$(cygpath -w "$here" 2>/dev/null || echo "$here")
tok=$(cygpath -w "$tokdir" 2>/dev/null || echo "$tokdir")

probe() { # <label> <rounds>
  MSYS_NO_PATHCONV=1 docker run --rm --network "$NET" -v "$here:/p:ro" -v "$tok:/s:ro" node:20-bookworm-slim \
    node /p/redis-outage-probe.mjs --targets "$targets" --tokens /s/tokens.json --event "$event" --seat "$seat" --label "$1" --rounds "$2" < /dev/null >> "$out"
}
recover() {
  docker unpause "$REDIS" >/dev/null 2>&1; docker start "$REDIS" >/dev/null 2>&1
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$REDIS")" = healthy ]; do sleep 2; done
  sleep 15 # 앱의 Redis 재연결
}

: > "$out"
probe baseline 1
echo "$(date '+%T') stop"; docker stop "$REDIS" >/dev/null; sleep 10; probe stop 2; recover
probe after-stop 1
echo "$(date '+%T') pause"; docker pause "$REDIS" >/dev/null; sleep 10; probe pause 2; recover
probe after-pause 1
echo "$(date '+%T') done"
