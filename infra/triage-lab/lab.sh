#!/usr/bin/env bash
# 장애 분석 도구용 로컬 실험실. 원인을 알고 주입한 예매 묶음 run을 만든다.
#
#   bash infra/triage-lab/lab.sh up                 # 이미지 빌드·기동·시드·토큰 발급·예열 1회
#   bash infra/triage-lab/lab.sh run <fault> [1|2]   # 주입 → 입장자 100명 예매 묶음 → 수집 → 주입 해제
#   bash infra/triage-lab/lab.sh clear              # 남은 주입 해제
#   bash infra/triage-lab/lab.sh down               # 실험실 종료(볼륨 포함)
#
# fault: none | redis | db | cpu, 강도 1(약)·2(강)
#   redis  Redis 컨테이너에 네트워크 지연(netem): 명령마다 응답이 늦는 상태
#   db     PostgreSQL 컨테이너 CPU 할당량 축소: DB가 CPU를 못 받아 쿼리가 느린 상태
#   cpu    예매 api 컨테이너 CPU 할당량 축소: 앱 CPU가 모자란 상태
# 강도는 주입 확인 run으로 정했다. 예매 묶음이 Redis·DB에 거는 부하가 작아, Redis CPU 0.1코어·지연 3~10ms,
# DB CPU 0.3코어·경쟁 컨테이너(가중치 4배)로는 예매 지연이 정상 범위였다(infra/triage-lab/README.md).
# 할당량 해제는 --cpus를 고정 코어 수로 되돌리는 방식이다(docker update --cpus 0은 제한을 풀지 않는다).
#
# 결과: artifacts/triage-lab/runs/<무작위 id>/ (sampler.jsonl, pg.tsv, wal.tsv, disk.txt, book.log, meta.json)
# disk.txt는 측정 대상과 분리된 컨테이너의 디스크 동기화 지연이다. 호스트 저장소가 느린 run을 가려내는 데 쓴다(IMP-043).
# PostgreSQL의 WAL 동기화 시간은 DB가 CPU를 못 받을 때도 부풀어, 측정 환경 판정에는 쓰지 않는다.
# 원인 라벨은 run 디렉터리 밖 artifacts/triage-lab/labels.jsonl에만 남긴다. 분석 도구가 라벨을 보지 않게 하기 위해서다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/artifacts/triage-lab"
DC=(docker compose -f "$HERE/compose.yml")
export JWT_SECRET="${JWT_SECRET:-triage-lab-secret-0123456789-0123456789-0123456789}"
EVENTS=80 USERS=1200 USERS_PER_RUN=100

ncpu() { echo "$1" | tr ',' '\n' | wc -l | tr -d ' '; }
API_N=$(ncpu "${LAB_API_CPUS:-2,3}") PG_N=$(ncpu "${LAB_PG_CPUS:-6,7}")
cid() { "${DC[@]}" ps -q "$1"; }
psqlc() { "${DC[@]}" exec -T postgres psql -U flowticket -d flowticket -At -F $'\t' "$@"; }

wait_health() {
  for port in 18080 18081; do
    for _ in $(seq 1 90); do curl -fs "localhost:$port/actuator/health" >/dev/null 2>&1 && break; sleep 2; done
    curl -fs "localhost:$port/actuator/health" >/dev/null || { echo "포트 $port 기동 실패" >&2; exit 1; }
  done
}

# 강도 1은 증상이 뚜렷한 수준, 2는 더 심한 수준이다.
#   redis: 지연(ms) / db: PostgreSQL 할당 코어 / cpu: api 할당 코어
level_value() {
  case "$1:$2" in
    redis:1) echo 30 ;;   redis:2) echo 60 ;;
    db:1) echo 0.05 ;;    db:2) echo 0.03 ;;
    cpu:1) echo 0.4 ;;    cpu:2) echo 0.25 ;;
    *) echo "알 수 없는 주입: $1 강도 $2" >&2; exit 2 ;;
  esac
}
NETSHOOT=nicolaka/netshoot:v0.13
netem() { MSYS_NO_PATHCONV=1 docker run --rm --net "container:$(cid redis)" --cap-add NET_ADMIN "$NETSHOOT" tc qdisc "$@"; }

inject() {
  local fault=$1 level=$2 v
  [ "$fault" = none ] && return 0
  v=$(level_value "$fault" "$level")
  case "$fault" in
    redis) netem add dev eth0 root netem delay "${v}ms" ;;
    db) docker update --cpus "$v" "$(cid postgres)" >/dev/null ;;
    cpu) docker update --cpus "$v" "$(cid api)" >/dev/null ;;
    *) echo "fault는 none|redis|db|cpu" >&2; exit 2 ;;
  esac
}

clear_all() {
  netem del dev eth0 root 2>/dev/null || true
  docker update --cpus "$PG_N" "$(cid postgres)" >/dev/null
  docker update --cpus "$API_N" "$(cid api)" >/dev/null
}

# run마다 새 공연, 사용자는 100명씩 돌려 쓴다(1인 한도는 공연 단위라 다른 공연이면 겹쳐도 된다).
next_slot() {
  local n=0
  [ -f "$OUT/state" ] && n=$(cat "$OUT/state")
  echo $((n + 1)) > "$OUT/state"
  [ $((n + 1)) -le "$EVENTS" ] || { echo "공연을 다 썼다. down 후 up으로 다시 시작한다" >&2; exit 1; }
  EVENT_ID=$(psqlc -c "select id from events where kopis_id = 'TRIAGE-LAB-$((n + 1))'")
  OFFSET=$(( (n * USERS_PER_RUN) % USERS ))
}

burst() { # <run 디렉터리>
  local dir=$1 sa sas w
  sa=$(date -u -d "+45 seconds" +%Y-%m-%dT%H:%M:%SZ); sas=$(date -u -d "$sa" +%s)
  w=$(cygpath -w "$ROOT" 2>/dev/null || echo "$ROOT")
  node "$HERE/sampler.mjs" "$dir/sampler.jsonl" 75 & local sp=$!
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "${LAB_PROBE_CPUS:-14}" -v "$w/infra/triage-lab:/lab:ro" debian:bookworm-slim \
    timeout 75 bash /lab/disk-probe.sh > "$dir/disk.txt" 2>/dev/null & local dp=$!
  ( sleep 15; "${DC[@]}" exec -T postgres psql -U flowticket -d flowticket -At -F $'\t' < "$HERE/pgwatch.sql" > "$dir/pg.tsv" 2>&1 ) & local pp=$!
  (
    q="select clock_timestamp(), wal_sync, wal_sync_time from pg_stat_wal"
    until [ "$(date -u +%s)" -ge $((sas - 3)) ]; do sleep 0.2; done; echo "before	$(psqlc -c "$q")" > "$dir/wal.tsv"
    until [ "$(date -u +%s)" -ge $((sas + 15)) ]; do sleep 0.2; done; echo "after	$(psqlc -c "$q")" >> "$dir/wal.tsv"
  ) & local wp=$!
  MSYS_NO_PATHCONV=1 docker run --rm --network ft-triage_default --cpuset-cpus "${LAB_K6_CPUS:-10,11}" -v "$w:/w" grafana/k6:0.54.0 run --log-format=raw \
    -e K6_BASE_URL=http://api:8080 -e QUEUE_BASE_URL=http://queue:8080 -e EVENT_ID="$EVENT_ID" -e USERS=/w/artifacts/triage-lab/tokens.json \
    -e USER_OFFSET="$OFFSET" -e START_AT="$sa" /w/infra/k6/booking-e2e.js > "$dir/book.log" 2>&1 || true
  wait "$sp" "$pp" "$wp" "$dp" || true
  printf '{"startAt":"%s","eventId":%s,"users":%s}\n' "$sa" "$EVENT_ID" "$USERS_PER_RUN" > "$dir/meta.json"
}

cmd=${1:-}
case "$cmd" in
  up)
    mkdir -p "$OUT/runs"
    "${DC[@]}" up -d --build
    wait_health
    psqlc -v events="$EVENTS" -v users="$USERS" < "$HERE/seed.sql" >/dev/null
    psqlc -c "\\copy (select id, email from users where email like 'loadseed+%' order by id) to stdout with (format csv)" > "$OUT/users.csv"
    node "$ROOT/infra/loadgen/mint-tokens.mjs" mint --users "$OUT/users.csv" --out "$OUT/tokens.json" --ttl 7200
    : > "$OUT/state"
    echo "==> 예열 1회(결과는 분석에서 뺀다)"
    next_slot; mkdir -p "$OUT/warmup"; burst "$OUT/warmup"
    echo "준비 완료"
    ;;
  run)
    fault=${2:?fault가 필요하다}; level=${3:-1}
    [ "$fault" = none ] && level=0
    node "$ROOT/infra/loadgen/mint-tokens.mjs" check --tokens "$OUT/tokens.json" --need 600
    next_slot
    id=$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
    dir="$OUT/runs/$id"; mkdir -p "$dir"
    trap clear_all EXIT
    inject "$fault" "$level"
    burst "$dir"
    clear_all; trap - EXIT
    printf '{"id":"%s","fault":"%s","level":%s,"eventId":%s,"at":"%s"}\n' "$id" "$fault" "$level" "$EVENT_ID" "$(date '+%F %T')" >> "$OUT/labels.jsonl"
    echo "$id"
    ;;
  clear) clear_all ;;
  down) "${DC[@]}" down -v ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
