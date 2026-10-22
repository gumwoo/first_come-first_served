#!/usr/bin/env bash
# 장애 분석 도구용 로컬 실험실. 원인을 알고 주입한 예매 묶음 run을 만든다.
#
#   bash infra/triage-lab/lab.sh up                 # 이미지 빌드·기동·시드·토큰 발급·예열 1회(다시 실행해도 된다)
#   bash infra/triage-lab/lab.sh run <fault> [1|2]   # 주입 → 입장자 100명 예매 묶음 → 수집 → 주입 해제
#   bash infra/triage-lab/lab.sh tokens             # 토큰만 다시 발급(유효 2시간)
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
# 라벨에는 강도 번호와 함께 실제 주입값·방법을 남긴다(강도 표가 바뀌어도 run의 조건이 남는다).
# 측정용 psql(세션 상태·WAL)은 PostgreSQL 컨테이너 밖 별도 컨테이너에서 돌린다. db 주입으로 줄인 CPU를 측정이 나눠 쓰지 않게 한다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/artifacts/triage-lab"
DC=(docker compose -f "$HERE/compose.yml")
export JWT_SECRET="${JWT_SECRET:-triage-lab-secret-0123456789-0123456789-0123456789}"
EVENTS=80 USERS=1200 USERS_PER_RUN=100

# cpuset 표기("2,3"·"2-3"·"0-1,4")의 코어 수
ncpu() {
  local n=0 part
  for part in ${1//,/ }; do
    if [[ $part == *-* ]]; then n=$((n + ${part#*-} - ${part%-*} + 1)); else n=$((n + 1)); fi
  done
  echo "$n"
}
API_N=$(ncpu "${LAB_API_CPUS:-2,3}") PG_N=$(ncpu "${LAB_PG_CPUS:-6,7}")
cid() { "${DC[@]}" ps -q "$1"; }
psqlc() { "${DC[@]}" exec -T postgres psql -U flowticket -d flowticket -v ON_ERROR_STOP=1 -At -F $'\t' "$@"; }
# 측정용: 같은 네트워크의 별도 컨테이너(프로브 코어)에서 접속한다.
psqlx() {
  MSYS_NO_PATHCONV=1 docker run --rm -i --network ft-triage_default --cpuset-cpus "${LAB_PROBE_CPUS:-14}" -e PGPASSWORD=flowticket postgres:16 \
    psql -h postgres -U flowticket -d flowticket -At -F $'\t' "$@"
}

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

# run마다 아직 선점 기록이 없는 공연 중 번호가 가장 작은 것을 쓴다(DB가 기준이라 up을 다시 해도 쓴 공연을 고르지 않는다).
# 사용자는 100명씩 돌려 쓴다(1인 한도는 공연 단위라 다른 공연이면 겹쳐도 된다).
next_slot() {
  local n
  n=$(psqlc -c "select min(substring(e.kopis_id from 12)::int) from events e where e.kopis_id like 'TRIAGE-LAB-%' and not exists (select 1 from seat_holds h where h.event_id = e.id)")
  [ -n "$n" ] || { echo "쓰지 않은 공연이 없다. down 후 up으로 다시 시작한다" >&2; exit 1; }
  EVENT_ID=$(psqlc -c "select id from events where kopis_id = 'TRIAGE-LAB-$n'")
  [ -n "$EVENT_ID" ] || { echo "공연 TRIAGE-LAB-$n을 찾지 못했다" >&2; exit 1; }
  OFFSET=$(( ((n - 1) * USERS_PER_RUN) % USERS ))
}

mint() {
  psqlc -c "\\copy (select id, email from users where email like 'loadseed+%' order by id) to stdout with (format csv)" > "$OUT/users.csv"
  node "$ROOT/infra/loadgen/mint-tokens.mjs" mint --users "$OUT/users.csv" --out "$OUT/tokens.json" --ttl 7200
}

burst() { # <run 디렉터리>
  local dir=$1 sa sas w
  sa=$(date -u -d "+45 seconds" +%Y-%m-%dT%H:%M:%SZ); sas=$(date -u -d "$sa" +%s)
  w=$(cygpath -w "$ROOT" 2>/dev/null || echo "$ROOT")
  node "$HERE/sampler.mjs" "$dir/sampler.jsonl" 85 & local sp=$!  # 예매 시작 +40초까지(분석 구간 최대 +30초보다 넉넉히)
  MSYS_NO_PATHCONV=1 docker run --rm --cpuset-cpus "${LAB_PROBE_CPUS:-14}" -v "$w/infra/triage-lab:/lab:ro" debian:bookworm-slim \
    timeout 85 bash /lab/disk-probe.sh > "$dir/disk.txt" 2>/dev/null & local dp=$!
  ( sleep 15; psqlx < "$HERE/pgwatch.sql" > "$dir/pg.tsv" 2>&1 ) & local pp=$!
  (
    q="select clock_timestamp(), wal_sync, wal_sync_time from pg_stat_wal"
    until [ "$(date -u +%s)" -ge $((sas - 3)) ]; do sleep 0.2; done; echo "before	$(psqlx -c "$q" < /dev/null)" > "$dir/wal.tsv"
    until [ "$(date -u +%s)" -ge $((sas + 15)) ]; do sleep 0.2; done; echo "after	$(psqlx -c "$q" < /dev/null)" >> "$dir/wal.tsv"
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
    mint
    echo "==> 예열 1회(결과는 분석에서 뺀다)"
    next_slot; rm -rf "$OUT/warmup"; mkdir -p "$OUT/warmup"; burst "$OUT/warmup"
    echo "준비 완료"
    ;;
  run)
    # 반복문 안에서 lab.sh run을 부르면 docker compose exec(-T여도 표준 입력은 붙는다)와 docker run -i가
    # 반복문의 입력을 읽어, 반복문이 첫 줄 뒤에 끝난다(2026-10-22 시험). run은 시작할 때 표준 입력을 끊는다.
    exec < /dev/null
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
    case "$fault" in
      none) value=null method=none ;;
      redis) value="\"$(level_value redis "$level")ms\"" method=netem ;;
      *) value="\"$(level_value "$fault" "$level") cpus\"" method=cpus ;;
    esac
    printf '{"id":"%s","fault":"%s","level":%s,"value":%s,"method":"%s","eventId":%s,"at":"%s"}\n' \
      "$id" "$fault" "$level" "$value" "$method" "$EVENT_ID" "$(date '+%F %T')" >> "$OUT/labels.jsonl"
    echo "$id"
    ;;
  tokens) mint ;;
  clear) clear_all ;;
  down) "${DC[@]}" down -v ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
