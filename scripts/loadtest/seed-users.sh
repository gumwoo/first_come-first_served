#!/usr/bin/env bash
# 부하 시험용 사용자 시드(loadtest-100k-plan §2.4 ①). 측정 세션마다 한 번 실행한다(RDS는 철거 때 함께 지워진다).
#
#   scripts/loadtest/seed-users.sh --count 100000 --out artifacts/loadtest/<session-id>/users.csv
#
# 클러스터 안에서 일회용 psql 파드를 띄워 RDS에 직접 넣는다. RDS는 프라이빗 서브넷이라 밖에서는 닿지 않는다.
# 접속 정보는 api와 같은 ConfigMap(flowticket-api-config)·Secret(flowticket-api-secrets)에서 받는다 —
# 이 스크립트는 자격증명을 보지도 출력하지도 않는다.
#
# 시드 계정에는 **비밀번호가 없다**(password_hash NULL). 로그인할 수 없는 계정이라, 공개 저장소에 비밀번호를
# 두지 않아도 된다. 토큰은 infra/loadgen/mint-tokens.mjs가 서명 키로 직접 만든다.
#
# 출력: "id,email" 줄. mint-tokens.mjs의 입력이다. 이 파일은 artifacts/ 아래에 둔다(.gitignore).
set -euo pipefail

COUNT=100000 OUT="" NS=flowticket
while [ $# -gt 0 ]; do
  case "$1" in
    --count) COUNT="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] || { echo "--out이 필요하다" >&2; exit 2; }
case "$COUNT" in ''|*[!0-9]*) echo "--count는 정수여야 한다" >&2; exit 2 ;; esac
mkdir -p "$(dirname "$OUT")"

# 이메일·휴대폰 번호는 유일 제약이 있다. 실제 번호와 겹치지 않도록 099로 시작하는 번호를 쓴다.
# ON CONFLICT DO NOTHING: 같은 세션에서 다시 돌려도 기존 계정을 그대로 둔다(멱등).
SQL="
INSERT INTO users (email, password_hash, name, phone, role, provider)
SELECT 'loadseed+' || g || '@example.com', NULL, 'loadseed' || g, '099' || lpad(g::text, 8, '0'), 'ROLE_USER', 'local'
  FROM generate_series(1, $COUNT) AS g
ON CONFLICT DO NOTHING;
\\copy (SELECT id, email FROM users WHERE email LIKE 'loadseed+%' ORDER BY id) TO STDOUT WITH (FORMAT csv)
"

# 파드 사양: DB 접속에 필요한 값만 받는다. 시크릿 전체(JWT·OAuth 키 포함)를 envFrom으로 받지 않는다(최소 권한).
# psql은 PG* 환경변수를 읽는다.
#
# stdinOnce: --overrides는 기본이 JSON Merge Patch라 containers 배열을 통째로 바꾼다. 그러면 kubectl run -i가 넣는
# stdinOnce가 사라지고, 파이프 입력이 끝나도 psql이 EOF를 받지 못해 끝나지 않는다. 그래서 직접 넣는다.
OVERRIDES="$(cat <<'EOF'
{
  "spec": {
    "restartPolicy": "Never",
    "containers": [{
      "name": "psql",
      "image": "postgres:16",
      "stdin": true,
      "stdinOnce": true,
      "env": [
        {"name": "PGHOST", "valueFrom": {"configMapKeyRef": {"name": "flowticket-api-config", "key": "DB_HOST"}}},
        {"name": "PGPORT", "valueFrom": {"configMapKeyRef": {"name": "flowticket-api-config", "key": "DB_PORT"}}},
        {"name": "PGDATABASE", "valueFrom": {"configMapKeyRef": {"name": "flowticket-api-config", "key": "DB_NAME"}}},
        {"name": "PGUSER", "valueFrom": {"secretKeyRef": {"name": "flowticket-api-secrets", "key": "DB_USERNAME"}}},
        {"name": "PGPASSWORD", "valueFrom": {"secretKeyRef": {"name": "flowticket-api-secrets", "key": "DB_PASSWORD"}}},
        {"name": "PGSSLMODE", "value": "prefer"}
      ],
      "command": ["psql", "-v", "ON_ERROR_STOP=1", "-q", "-At", "-f", "-"]
    }]
  }
}
EOF
)"

echo "==> 시드 계정 $COUNT개 넣기 → $OUT"
# 노드가 postgres 이미지를 처음 받으면 기본 대기(1분)를 넘길 수 있다(추론).
printf '%s' "$SQL" | kubectl run loadseed-psql -n "$NS" --rm -i --quiet --restart=Never \
  --pod-running-timeout=5m --image=postgres:16 --overrides="$OVERRIDES" > "$OUT"

n="$(grep -c '^[0-9]' "$OUT" || true)"
echo "    시드 계정 $n개(loadseed+*) — 목표 $COUNT"
[ "$n" -ge "$COUNT" ] || { echo "시드 계정이 목표보다 적다" >&2; exit 1; }
