#!/usr/bin/env bash
# Alertmanager 설정 검증 — **webhook 없이, 클러스터 없이** 라우팅을 판정한다.
#
# 왜 필요한가: 규칙(promtool)은 검증하는데 **라우팅은 아무도 보지 않았다.** 규칙이 옳아도
# 라우트를 잘못 쓰면 알림이 엉뚱한 리시버로 가거나 아무 데도 안 간다. 배포 전에는 증상이 없다.
#
# 특히 Watchdog은 **항상 발화하는** 알림이라, null로 빠지지 않으면 Slack이 끊임없이 울린다.
# 그건 "알림을 붙였더니 소음이 됐다"는 실패의 가장 빠른 경로다.
set -euo pipefail

IMAGE="prom/alertmanager:v0.27.0"   # 버전 고정 = 결정론(TS-027)
DIR="${1:?usage: check-alertmanager.sh <설정이 있는 디렉터리>}"
DIR="$(cd "$DIR" && pwd)"
# Git Bash에서는 마운트 원본을 Windows 경로로 줘야 Docker가 인식한다(리눅스에선 그대로).
command -v cygpath >/dev/null 2>&1 && DIR="$(cygpath -m "$DIR")"

# Git Bash가 컨테이너 **안쪽** 경로(/w, /bin/amtool)를 Windows 경로로 바꾸는 것을 막는다.
# 리눅스(CI)에서는 아무 영향이 없다.
export MSYS_NO_PATHCONV=1

amtool() {
  docker run --rm --entrypoint /bin/amtool -v "$DIR:/w" -w /w "$IMAGE" "$@"
}

echo "==> amtool check-config"
amtool check-config alertmanager.yml

echo "==> 라우팅 판정"
fail=0
expect_route() {
  local expected="$1"; shift
  local got
  got="$(amtool config routes test --config.file=alertmanager.yml "$@" | tr -d '\r' | tail -1)"
  if [ "$got" != "$expected" ]; then
    echo "  ✗ [$*] → '$got' (기대: '$expected')" >&2
    fail=1
  else
    echo "  ✓ [$*] → $expected"
  fi
}

# Watchdog은 반드시 버려야 한다. 여기가 깨지면 Slack이 계속 울린다.
expect_route "null"  alertname=Watchdog
# 나머지는 severity와 무관하게 한 채널로 간다(채널 1개 설계 — webhook 하나 = 채널 하나).
expect_route "slack" alertname=FlowticketHighServerErrorRate severity=critical
expect_route "slack" alertname=FlowticketOutboxDeadRows severity=warning
# 라벨이 없는 알림도 기본 라우트로 떨어져야 한다 — 조용히 사라지면 안 된다.
expect_route "slack" alertname=SomethingUnexpected

[ "$fail" -eq 0 ] || { echo "라우팅이 기대와 다르다" >&2; exit 1; }
echo "==> Alertmanager 설정 검증 통과"
