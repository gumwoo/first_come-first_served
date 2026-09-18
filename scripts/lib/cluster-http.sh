#!/usr/bin/env bash
# 측정 스크립트 공용: 서비스에 HTTP로 물어보되, 로컬 네트워크에 의존하지 않는다.
#
# 측정 부하는 클러스터 안 k6가 만든다. 전제 확인까지 로컬 curl에 맡기면 로컬 리졸버가 도메인을
# 못 풀 때 동작하는 시스템을 재지 못한다.
#
# 로컬을 먼저 쓰는 이유는 빠르기 때문이다(파드 기동 없이 즉시). 실패할 때만 클러스터로 넘어간다.
#
# 사용:
#   . "$ROOT/scripts/lib/cluster-http.sh"
#   CODE="$(http_code "https://$DOMAIN")"
#   BODY="$(http_body "https://$DOMAIN/api/events?status=ON_SALE&size=20")"

: "${CURL_IMAGE:=curlimages/curl:8.10.1}"
: "${PROBE_NS:=flowticket}"
: "${LOCAL_TRIES:=3}"

# 클러스터 안에서 curl 한 번. 파드 이름이 겹치지 않도록 매번 다르게 짓는다.
_incluster_curl() {
  kubectl -n "$PROBE_NS" run "probe-$$-$RANDOM" --rm -i --restart=Never \
    --image="$CURL_IMAGE" --timeout=120s --quiet -- "$@" 2>/dev/null
}

# HTTP 상태코드만. 실패하면 000.
#
# `curl ... || echo 000` 으로 쓰지 않는다. curl은 DNS 실패에서도 -w 때문에 "000"을
# 출력하면서 non-zero로 끝난다. 그래서 `|| echo 000`이 덧붙어 "000000"이 되고,
# `[ "$c" != "000" ]`가 참이 되어 폴백이 영영 실행되지 않는다.
# 종료 코드와 HTTP 코드를 분리해서 본다.
http_code() {
  url="$1"; i=1
  while [ "$i" -le "$LOCAL_TRIES" ]; do
    if c="$(curl -s -o /dev/null -w '%{http_code}' "$url" --max-time 15 2>/dev/null)"; then
      [ -n "$c" ] && [ "$c" != "000" ] && { printf '%s' "$c"; return 0; }
    fi
    i=$((i + 1)); sleep 2
  done
  # 로컬이 안 되면 클러스터 안에서. 여기까지 왔다는 것은 로컬 문제일 가능성이 높다.
  c="$(_incluster_curl -s -o /dev/null -w '%{http_code}' "$url" --max-time 20 | tr -dc '0-9')"
  printf '%s' "${c:-000}"
}

# 응답 본문. 실패하면 빈 문자열.
http_body() {
  url="$1"; i=1
  while [ "$i" -le "$LOCAL_TRIES" ]; do
    b="$(curl -s "$url" --max-time 20 2>/dev/null || true)"
    [ -n "$b" ] && { printf '%s' "$b"; return 0; }
    i=$((i + 1)); sleep 2
  done
  _incluster_curl -s "$url" --max-time 25
}
