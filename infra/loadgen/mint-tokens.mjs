#!/usr/bin/env node
// 부하 시험용 access token 발급·점검(loadtest-100k-plan §2.4 ①).
//
// 왜 로그인으로 받지 않는가: 로그인은 BCrypt 검증이라 CPU를 쓴다. 10만 명을 로그인시키면 측정 대상 클러스터에
// 큰 부하를 얹고, access token 유효기간(access-token-ttl 1,800초)이 짧아 측정 세션 동안 여러 번 반복해야 한다.
// 인증 필터(JwtAuthenticationFilter)는 DB를 보지 않고 서명과 클레임만 검증하므로, 앱과 같은 서명 키로
// 같은 형식의 토큰을 만들면 앱이 발급한 것과 구별되지 않는다. 그래서 측정 대상에 부하 없이 몇 초 만에 만든다.
//
// 형식은 JwtProvider.createAccessToken과 같다: sub=사용자 ID, type=access, email, role, iat, exp.
// 유효기간은 앱 설정(access-token-ttl)과 같은 1,800초를 기본으로 둔다 — 측정 조건을 바꾸지 않는다.
// 형식이 어긋나면 진입 요청이 401로 떨어지므로, 앱 쪽 계약 테스트(LoadgenTokenContractTest)가 이 출력을 검증한다.
//
// 서명 키는 환경변수 JWT_SECRET으로만 받는다. 파일·로그·인자로 받지 않는다(CLAUDE.md "비밀은 환경변수로만").
//
//   발급: JWT_SECRET=... node infra/loadgen/mint-tokens.mjs mint --users users.csv --out tokens.json [--ttl 1800]
//         users.csv = "id,email" 줄(scripts/loadtest/seed-users.sh가 만든다)
//   점검: node infra/loadgen/mint-tokens.mjs check --tokens tokens.json --need 600
//         가장 먼저 만료되는 토큰의 남은 시간이 --need초보다 짧으면 종료 코드 1. run 시작 전에 돌린다.
import { createHmac } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

const b64url = (buf) => Buffer.from(buf).toString("base64url");

// jjwt의 signWith(key)는 키 길이로 알고리즘을 고른다(Keys.hmacShaKeyFor). 같은 규칙을 따른다.
export function algFor(keyBytes) {
  const bits = keyBytes.length * 8;
  if (bits >= 512) return ["HS512", "sha512"];
  if (bits >= 384) return ["HS384", "sha384"];
  if (bits >= 256) return ["HS256", "sha256"];
  throw new Error(`서명 키가 256비트보다 짧다(${bits}비트). 앱도 이 키로 기동하지 못한다`);
}

export function mint({ key, userId, email, iat, ttl }) {
  const keyBytes = Buffer.from(key, "utf8");
  const [alg, hash] = algFor(keyBytes);
  const header = b64url(JSON.stringify({ alg }));
  const payload = b64url(JSON.stringify({
    sub: String(userId), type: "access", email, role: "ROLE_USER", iat, exp: iat + ttl,
  }));
  const sig = createHmac(hash, keyBytes).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${sig}`;
}

export function expOf(token) {
  return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8")).exp;
}

function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  const { values: a } = parseArgs({
    args: rest,
    options: {
      users: { type: "string" }, out: { type: "string" }, tokens: { type: "string" },
      ttl: { type: "string", default: "1800" }, need: { type: "string", default: "600" },
      iat: { type: "string" }, // 계약 테스트용 고정 시각. 실제 발급에서는 쓰지 않는다
    },
  });

  if (cmd === "mint") {
    const key = process.env.JWT_SECRET;
    if (!key) throw new Error("JWT_SECRET 환경변수가 필요하다");
    if (!a.users || !a.out) throw new Error("--users와 --out이 필요하다");
    const iat = a.iat ? Number(a.iat) : Math.floor(Date.now() / 1000);
    const ttl = Number(a.ttl);
    const rows = readFileSync(a.users, "utf8").split(/\r?\n/).filter((l) => /^\d+,/.test(l));
    // n은 사용자 순번(1부터). 기존 토큰 파일(seed-issue-tokens.js)과 같은 {n, t} 형식이라 k6 스크립트가 그대로 읽는다.
    const tokens = rows.map((line, i) => {
      const [id, email] = line.split(",");
      return { n: i + 1, t: mint({ key, userId: id, email, iat, ttl }) };
    });
    writeFileSync(a.out, JSON.stringify(tokens));
    console.error(`[mint] ${tokens.length}개 발급, 만료 ${new Date((iat + ttl) * 1000).toISOString()}`);
    return;
  }

  if (cmd === "check") {
    if (!a.tokens) throw new Error("--tokens가 필요하다");
    const tokens = JSON.parse(readFileSync(a.tokens, "utf8"));
    const now = Math.floor(Date.now() / 1000);
    const minExp = tokens.reduce((m, x) => Math.min(m, expOf(x.t)), Infinity);
    const left = minExp - now;
    console.log(JSON.stringify({ tokens: tokens.length, minRemainingSeconds: left, needSeconds: Number(a.need) }));
    if (!(left >= Number(a.need))) {
      console.error(`[check] 남은 유효시간 ${left}초 < 필요 ${a.need}초 — run 전에 다시 발급한다`);
      process.exit(1);
    }
    return;
  }

  console.error("사용: mint-tokens.mjs mint --users <csv> --out <json> [--ttl 1800] | check --tokens <json> --need <초>");
  process.exit(2);
}

// 테스트가 import할 때는 실행하지 않는다.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
