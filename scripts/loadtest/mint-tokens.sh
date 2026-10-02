#!/usr/bin/env bash
# 시드 사용자 토큰 발급(loadtest-100k-plan §2.4 ①). 서명 키를 SSM에서 꺼내 환경변수로만 넘긴다.
#
#   bash scripts/loadtest/mint-tokens.sh --users artifacts/loadtest/<session-id>/users.csv \
#                                        --out   artifacts/loadtest/<session-id>/tokens.json
#
# 키 값은 화면·파일·명령 인자에 남기지 않는다. 이 셸 변수에만 잠깐 있고 node 프로세스의 환경으로 넘어간다.
# 토큰 파일 자체도 자격증명이다 — artifacts/ 아래(.gitignore)에만 두고, 발생기에는 loadgen.sh push로 나눠 준다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REGION="${AWS_REGION:-ap-northeast-2}"
PARAM=/flowticket/JWT_SECRET

USERS="" OUT="" TTL=1800
while [ $# -gt 0 ]; do
  case "$1" in
    --users) USERS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --ttl) TTL="$2"; shift 2 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[ -n "$USERS" ] && [ -n "$OUT" ] || { echo "--users와 --out이 필요하다" >&2; exit 2; }

# Git Bash(Windows)는 /로 시작하는 인자를 Windows 경로로 바꾼다. 파라미터 이름이 그 모양이라 이 명령에만 끈다
# (k8s/external-secrets/bootstrap.sh와 같은 이유).
JWT_SECRET="$(MSYS_NO_PATHCONV=1 aws ssm get-parameter --region "$REGION" --name "$PARAM" --with-decryption \
  --query Parameter.Value --output text)"
[ -n "$JWT_SECRET" ] || { echo "서명 키를 읽지 못했다: $PARAM" >&2; exit 1; }

JWT_SECRET="$JWT_SECRET" node "$ROOT/infra/loadgen/mint-tokens.mjs" mint --users "$USERS" --out "$OUT" --ttl "$TTL"
unset JWT_SECRET
