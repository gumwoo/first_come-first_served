#!/usr/bin/env bash
# 측정 세션 진단 도구(ADR-023 실행 순서 ①). 세션 한정 변경만 하고, 되돌리는 명령을 같이 둔다.
# 절차·판정과의 관계는 docs/testing/loadtest-diag-runbook.md.
#
#   infra/loadgen/diag/diag.sh nlb-up        내부 NLB 두 개(web 경유·api 직접)를 만들고 주소를 낸다
#   infra/loadgen/diag/diag.sh nlb-status    주소·타깃 상태
#   infra/loadgen/diag/diag.sh nlb-down      지운다(세션 끝에 반드시)
#   infra/loadgen/diag/diag.sh jfr-on <초> [지연초]   api에 JFR을 켠다(JDK_JAVA_OPTIONS — 롤아웃이 일어나 파드가 새로 뜬다).
#                                            JVM 기동 + 지연초부터 <초> 동안 녹화한다(지연 기본 0 — 부팅·워밍업을 빼려면 준다)
#   infra/loadgen/diag/diag.sh jfr-collect <디렉터리>   파드마다 녹화 파일을 회수한다(녹화가 끝난 파드만)
#   infra/loadgen/diag/diag.sh jfr-off       JFR을 끈다(롤아웃)
#
# JFR은 JAVA_OPTS가 아니라 JDK_JAVA_OPTIONS로 넣는다. 이미지의 ENV JAVA_OPTS(-XX:MaxRAMPercentage=75)를 컨테이너
# env로 덮으면 힙 설정이 사라져 측정이 오염된다. JDK_JAVA_OPTIONS는 java 런처가 명령줄 앞에 붙인다(회수한 JFR의
# jvmArguments로 확인) — 같은 옵션이 겹치면 뒤에 오는 JAVA_OPTS가 우선이다.
set -euo pipefail
# Windows Git Bash(MSYS)는 인자 안의 /tmp/… 를 Windows 경로로 바꿔 kubectl.exe에 넘긴다. 컨테이너 경로가 C:/Users/…로
# 바뀌면 JVM이 녹화 파일을 못 만들어 기동에 실패한다(측정 세션 20261005-1440에서 CrashLoopBackOff로 확인). 리눅스에서는 무시된다.
# 대신 로컬 파일 경로도 변환되지 않으므로 kubectl에는 로컬 절대 경로를 넘기지 않는다(매니페스트는 stdin, 회수는 상대 경로).
export MSYS_NO_PATHCONV=1
NS=flowticket
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JFR_FILE=/tmp/flowticket-diag.jfr

addr() { kubectl -n "$NS" get svc "$1" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true; }
# 종료 중(deletionTimestamp 있음)인 이전 파드는 뺀다 — 롤아웃 직후 목록에 남아 있을 수 있다.
api_pods() {
  kubectl -n "$NS" get pods -l app=flowticket-api \
    -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.deletionTimestamp}{"\n"}{end}' | awk 'NF==1{print $1}'
}

case "${1:-}" in
  nlb-up)
    kubectl apply -f - < "$HERE/diag-nlb.yaml"
    a=""; w=""
    for _ in $(seq 1 60); do
      a="$(addr flowticket-api-diag)"; w="$(addr flowticket-web-diag)"
      [ -n "$a" ] && [ -n "$w" ] && break; sleep 5
    done
    [ -n "$a" ] && [ -n "$w" ] || { echo "5분 안에 NLB 주소가 나오지 않았다 — 'diag.sh nlb-status'·Service 이벤트를 본다" >&2; exit 1; }
    echo "api-direct=http://$a"
    echo "web-proxy=http://$w/api"
    echo "주소가 나와도 연결까지 수 분 걸린다 — 발생기에서 api-direct/actuator/health/liveness·web-proxy/events가 200인지 확인한 뒤 run을 건다."
    ;;
  nlb-status)
    kubectl -n "$NS" get svc -l flowticket.io/purpose=loadtest-diag -o wide
    ;;
  nlb-down)
    kubectl delete --ignore-not-found -f - < "$HERE/diag-nlb.yaml"
    ;;
  jfr-on)
    sec="${2:?녹화 시간(초)이 필요하다}"; delay="${3:-0}"
    # duration=0은 JFR에서 "끝없이 녹화"라 회수 시점이 없다 — 1 이상만 받는다.
    case "$sec" in ""|*[!0-9]*|0*) echo "녹화 시간은 1 이상의 정수 초다: $sec" >&2; exit 2 ;; esac
    case "$delay" in ""|*[!0-9]*) echo "지연은 0 이상의 정수 초다: $delay" >&2; exit 2 ;; esac
    opts="-XX:StartFlightRecording=duration=${sec}s,filename=${JFR_FILE},settings=profile"
    if [ "$delay" -gt 0 ]; then
      opts="-XX:StartFlightRecording=delay=${delay}s,duration=${sec}s,filename=${JFR_FILE},settings=profile"
    fi
    kubectl -n "$NS" set env deploy/flowticket-api JDK_JAVA_OPTIONS="$opts"
    # 롤링이라 새 파드가 못 뜨면 기존 파드는 남는다. 기동 실패면 오래 기다리지 말고 되돌린다.
    kubectl -n "$NS" rollout status deploy/flowticket-api --timeout=300s || {
      echo "롤아웃 실패 — 새 파드 로그를 확인하고 'diag.sh jfr-off'로 되돌린다." >&2; exit 1; }
    echo "JFR 켜짐: JVM 기동 + ${delay}초부터 ${sec}초 녹화. 파드마다 힙 설정(cmdline)·JFR 로그 확인:"
    # JDK_JAVA_OPTIONS는 런처가 환경변수에서 읽으므로 /proc/1/cmdline에는 나오지 않는다 — 녹화는 로그(jfr,startup)로 본다.
    for p in $(api_pods); do
      heap="$(kubectl -n "$NS" exec "$p" -c api -- sh -c 'tr "\0" " " < /proc/1/cmdline' | grep -o 'MaxRAMPercentage=[0-9]*' || echo '힙 설정 없음!')"
      rec="$(kubectl -n "$NS" logs "$p" -c api | grep '\[jfr,startup\]' | grep -v '\] *$' | sed -n 1p | sed 's/.*\] *//' | grep . || echo 'JFR 로그 없음!')"
      echo "  $p  $heap  $rec"
    done
    ;;
  jfr-collect)
    out="${2:?저장 디렉터리가 필요하다}"; mkdir -p "$out"
    o="$(kubectl -n "$NS" get deploy/flowticket-api -o jsonpath='{.spec.template.spec.containers[?(@.name=="api")].env[?(@.name=="JDK_JAVA_OPTIONS")].value}' || true)"
    dur="$(echo "$o" | grep -o 'duration=[0-9]*' | cut -d= -f2 || true)"
    delay="$(echo "$o" | grep -o 'delay=[0-9]*' | cut -d= -f2 || true)"; delay="${delay:-0}"
    [ -n "$dur" ] || { echo "JFR이 켜져 있지 않다(jfr-on 먼저)" >&2; exit 1; }
    fail=0
    for p in $(api_pods); do
      # 녹화가 끝나기 전 파일은 비었거나 덜 쓰였다 — 컨테이너(JVM) 기동 + 지연 + duration(+10초)이 지나지 않았으면 건너뛴다.
      st="$(kubectl -n "$NS" get pod "$p" -o jsonpath='{.status.containerStatuses[?(@.name=="api")].state.running.startedAt}')"
      [ -n "$st" ] || { echo "회수 실패: $p(api 컨테이너가 실행 중이 아니다)" >&2; fail=1; continue; }
      left=$(( $(date -d "$st" +%s) + delay + dur + 10 - $(date +%s) ))
      if [ "$left" -gt 0 ]; then echo "건너뜀: $p 녹화 중(${left}초 뒤 다시)" >&2; fail=1; continue; fi
      # 로컬 경로는 상대 경로로 넘긴다 — 절대 경로는 Windows에서 변환 여부에 따라 엉뚱한 곳(C:\c\…)에 쓰인다.
      if (cd "$out" && kubectl -n "$NS" cp "$p:$JFR_FILE" "$p.jfr" -c api 2>/dev/null) && [ -s "$out/$p.jfr" ]; then
        echo "회수: $out/$p.jfr ($(wc -c < "$out/$p.jfr") bytes)"
      else
        rm -f "$out/$p.jfr"   # 빈·부분 파일을 남기면 분석에 섞인다
        echo "회수 실패: $p(파일 없음·빈 파일 — 파드가 재시작했을 수 있다)" >&2; fail=1
      fi
    done
    exit "$fail"
    ;;
  jfr-off)
    kubectl -n "$NS" set env deploy/flowticket-api JDK_JAVA_OPTIONS-
    kubectl -n "$NS" rollout status deploy/flowticket-api --timeout=600s
    ;;
  *)
    sed -n '2,15p' "$0"; exit 2 ;;
esac
