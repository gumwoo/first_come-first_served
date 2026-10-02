// 발급기 쪽 계약 테스트: node --test infra/loadgen/mint-tokens.test.mjs
//
// 같은 입력이면 같은 토큰이 나와야 한다(HMAC은 결정적). 앱 쪽 LoadgenTokenContractTest가 검증하는 fixture를
// 이 발급기가 그대로 다시 만드는지 본다. 둘 중 한쪽만 바뀌면 둘 중 하나가 깨진다.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { algFor, expOf, mint } from "./mint-tokens.mjs";

const FIXTURE = new URL("../../apps/api/src/test/resources/loadgen/contract-token.txt", import.meta.url);
const CONTRACT_KEY = "loadgen-contract-test-key-not-a-real-secret-0123456789-abcdefgh";

test("앱 계약 테스트의 fixture를 그대로 다시 만든다", () => {
  const t = mint({ key: CONTRACT_KEY, userId: 42, email: "loadseed+42@example.com", iat: 1700000000, ttl: 2402444800 });
  assert.equal(t, readFileSync(FIXTURE, "utf8").trim());
});

test("알고리즘은 jjwt처럼 키 길이로 고른다", () => {
  assert.equal(algFor(Buffer.alloc(32))[0], "HS256");
  assert.equal(algFor(Buffer.alloc(48))[0], "HS384");
  assert.equal(algFor(Buffer.alloc(64))[0], "HS512");
  assert.throws(() => algFor(Buffer.alloc(31)));
});

test("만료는 발급 시각 + TTL이다(앱 설정 1,800초를 기본으로 쓴다)", () => {
  const t = mint({ key: CONTRACT_KEY, userId: 1, email: "loadseed+1@example.com", iat: 1000, ttl: 1800 });
  assert.equal(expOf(t), 2800);
});
