#!/usr/bin/env bash
# 데모 데이터 시딩 — KOPIS 동기화로 공연·좌석을 채운다.
#
# 왜 스크립트인가: 클러스터를 재생성하면 RDS도 새로 만들어져 **DB가 비어 있다.** 그런데
# 이 두 엔드포인트에는 화면이 없어서(백엔드만 존재), 지금까지는 브라우저 콘솔에 fetch를
# 붙여넣는 방식으로 사람이 매번 했다. 재현 가능한 절차가 아니다.
#
# 자격증명은 **SSM에서 읽고 출력하지 않는다.** 저장소·로그·셸 히스토리에 남기지 않는다.
#
# ⚠️ **"전부 채운다"가 아니라 "부하 측정이 성립할 만큼 채운다"이다.** 세 층이 모두 부분적이다.
#   목록: 오늘~+90일, 31일 청크, 최대 10페이지 (kopis.sync.days / max-pages)
#   상세: 1회에 끝나지 않는다 — best-effort이고 남은 건 detailSyncedAt이 NULL로 남아
#         **다음 회차가 이어받는다.** 상세를 다 채우려면 이 스크립트를 여러 번 돌려야 한다.
#   좌석: 동기화가 seedSellable()로 자동 시딩하고, 아래 확인은 **앞 20건만** 본다.
#         20이 임의값이 아니다 — k6의 discoverEvent()가 `size=20`에서 좌석 있는 공연을
#         고르므로(infra/k6/lib.js), 부하 측정이 보는 범위와 정확히 같다.
#
# 전제: kubeconfig 설정됨, 앱이 https://<도메인> 으로 응답함.
set -euo pipefail

BASE="${FLOWTICKET_BASE:-https://flow-ticket.com}"
API="$BASE/api"
# Git Bash(Windows)는 `/`로 시작하는 인자를 Windows 경로로 바꾼다 — SSM 이름이 그 모양이라
# 끄지 않으면 ParameterNotFound가 난다(bootstrap.sh와 같은 이유).
ssm() { MSYS_NO_PATHCONV=1 aws ssm get-parameter --name "$1" --with-decryption --query 'Parameter.Value' --output text | tr -d '\r'; }
# ⚠️ Git Bash의 jq는 출력 끝에 CR을 붙인다. 그대로 URL에 넣으면 경로 중간에 CR이 들어가
# curl이 "Malformed input to a URL function"으로 죽는다(2026-08-21에 실제로 걸렸다).
jqr() { jq -r "$@" | tr -d '\r'; }

echo "==> 1/4 관리자 자격증명 조회(SSM)"
ADMIN_EMAIL="$(ssm /flowticket/ADMIN_EMAIL)"
ADMIN_PASSWORD="$(ssm /flowticket/ADMIN_PASSWORD)"
[ -n "$ADMIN_EMAIL" ] && [ -n "$ADMIN_PASSWORD" ] || { echo "관리자 자격증명이 비어 있다" >&2; exit 1; }
echo "    ${ADMIN_EMAIL%%@*}@… (비밀번호는 출력하지 않는다)"

echo "==> 2/4 로그인"
LOGIN_BODY="$(jq -cn --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email:$e,password:$p,remember:true}')"
TOKEN="$(curl -sS -X POST "$API/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY" \
  | jqr '.data.accessToken // empty')"
unset ADMIN_PASSWORD LOGIN_BODY
[ -n "$TOKEN" ] || { echo "로그인 실패 — SSM의 ADMIN_EMAIL/ADMIN_PASSWORD를 확인하라" >&2; exit 1; }
echo "    access token 획득"

echo "==> 3/4 KOPIS 동기화 트리거"
# ⚠️ 응답을 기다리지 않는다. 동기화는 약 3분 45초 걸리는데 ALB idle timeout이 60초라
# **504가 떠도 서버는 계속 처리한다**(TS-031). 그래서 성공/실패를 응답으로 판단하지 않고
# 아래에서 **데이터가 실제로 들어왔는지**로 판단한다.
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$API/admin/sync/kopis" \
  -H "Authorization: Bearer $TOKEN" --max-time 70 || true)"
case "$CODE" in
  200) echo "    동기화 완료 응답(200)" ;;
  409) echo "    이미 다른 동기화가 진행 중이다(409) — 그대로 기다린다" ;;
  504|000) echo "    504/타임아웃 — 정상이다(ALB 60초 < 동기화 3분 45초, TS-031). 계속 진행 중" ;;
  *)   echo "    예상 밖 응답: $CODE — 아래 데이터 확인으로 판정한다" ;;
esac

echo "==> 4/4 데이터가 들어올 때까지 확인 (최대 8분)"
DEADLINE=$(( $(date +%s) + 480 ))
ON_SALE=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  ON_SALE="$(curl -sS "$API/events?status=ON_SALE&size=1" --max-time 20 | jqr '.data.total // 0')"
  [ "$ON_SALE" -gt 0 ] && break
  printf '    ON_SALE 0건 — 대기 중…\r'
  sleep 15
done
echo
[ "$ON_SALE" -gt 0 ] || { echo "8분 안에 공연이 들어오지 않았다. api 파드 로그를 확인하라" >&2; exit 1; }
echo "    ON_SALE 공연 $ON_SALE건"

# 좌석은 동기화가 자동 시딩한다(SeatSeeder). 누락분만 보조로 채운다 — 엔드포인트가 멱등이다.
# 범위는 k6가 보는 앞 20건과 같다(위 주석 참고).
echo "==> 좌석 확인 및 누락분 시딩 (k6가 보는 앞 20건)"
IDS="$(curl -sS "$API/events?status=ON_SALE&size=20" --max-time 20 | jqr '.data.items[].id')"
seeded=0; already=0
for id in $IDS; do
  n="$(curl -sS "$API/events/$id/seats" --max-time 20 | jqr '(.data.seats // []) | length')"
  if [ "${n:-0}" -eq 0 ]; then
    curl -sS -o /dev/null -X POST "$API/admin/events/$id/seats" -H "Authorization: Bearer $TOKEN" --max-time 60 || true
    seeded=$((seeded+1))
  else
    already=$((already+1))
  fi
done
echo "    좌석 있음 ${already}건 / 보조 시딩 ${seeded}건"
unset TOKEN

echo
echo "완료. 부하 측정을 돌릴 수 있는 상태다."
