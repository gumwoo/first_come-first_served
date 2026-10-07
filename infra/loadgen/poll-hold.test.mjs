// 대기자 폴링 발생기(poll-hold.mjs): 다음 조회 간격 규칙(프론트 useQueue와 같음), 토큰 참조(QueueAudit.ref와 같음),
// 그리고 mock 서버를 상대로 한 동작(입장·만료로 끝남, 토큰이 아닌 줄은 그대로 넘김, 결과 파일).
//   node --test infra/loadgen/poll-hold.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { nextDelay, nextErrorDelay, ref } from "./poll-hold.mjs";

const HOLD = fileURLToPath(new URL("./poll-hold.mjs", import.meta.url));
const MOCK = fileURLToPath(new URL("./mock-queue-server.mjs", import.meta.url));

test("ref는 SHA-256 앞 8바이트 hex다(QueueAudit.ref와 같은 값)", () => {
  // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
  assert.equal(ref("abc"), "ba7816bf8f01cfea");
});

test("다음 조회 = max(최소, retryAfterMs) + [0, jitter) — 더하기만 한다", () => {
  assert.equal(nextDelay(30_000, 2_000, 0.2, () => 0), 30_000);
  assert.equal(nextDelay(30_000, 2_000, 0.2, () => 0.999999), 35_999);
  assert.equal(nextDelay(500, 2_000, 0.2, () => 0), 2_000); // 최소 간격 아래로 내려가지 않는다
  assert.equal(nextDelay(undefined, 2_000, 0.2, () => 0), 2_000); // 구버전 응답(필드 없음)
});

test("오류 간격은 최소부터 두 배씩, 상한 30초", () => {
  const seq = [];
  let d = 0;
  for (let i = 0; i < 6; i++) seq.push((d = nextErrorDelay(d, 2_000)));
  assert.deepEqual(seq, [2_000, 4_000, 8_000, 16_000, 30_000, 30_000]);
});

function listen(child) {
  return new Promise((resolve, reject) => {
    child.stderr.on("data", (b) => {
      const m = String(b).match(/:(\d+) capacity/);
      if (m) resolve(Number(m[1]));
    });
    child.on("exit", (c) => reject(new Error(`mock 종료 ${c}`)));
  });
}

test("mock 상대로: 대기 토큰은 입장하면 끝나고, 모르는 토큰은 410으로 끝나며, 다른 줄은 그대로 넘긴다", async () => {
  const port = 18_000 + Math.floor(Math.random() * 1_000);
  const mock = spawn(process.execPath, [MOCK, "--port", String(port), "--capacity", "0", "--admit-after-ms", "1500", "--retry-after-ms", "100"]);
  try {
    await listen(mock);
    const base = `http://127.0.0.1:${port}`;
    const tokens = [];
    const issuedAt = Date.now(); // mock은 발급 시각 + 1,500ms에 입장시킨다(발급 POST 직전 시각 — 가장 이른 값)
    for (let i = 0; i < 3; i++) {
      const r = await fetch(`${base}/events/1/queue/token`, { method: "POST", headers: { Authorization: "Bearer t" } });
      tokens.push((await r.json()).data.token);
    }
    const out = join(mkdtempSync(join(tmpdir(), "poll-hold-")), "poll-g1");
    const hold = spawn(process.execPath, [HOLD, "--base", base, "--out", out, "--hold", "10", "--min-ms", "50", "--jitter", "0"]);
    let stdout = "";
    hold.stdout.on("data", (b) => (stdout += b));
    for (const t of tokens) hold.stdin.write(`QTOKEN ${t} 100\n`);
    hold.stdin.write("QTOKEN not-issued-token 100\n");
    hold.stdin.write("k6 로그 한 줄\n");
    hold.stdin.end();
    const code = await new Promise((r) => hold.on("exit", r));
    assert.equal(code, 0);
    assert.match(stdout, /k6 로그 한 줄/);

    const summary = JSON.parse(readFileSync(join(out, "summary.json"), "utf8"));
    assert.equal(summary.reason, "모든 토큰이 끝났다");
    assert.equal(summary.tokens, 4);
    assert.deepEqual(summary.finalStatus, { ADMITTED: 3, EXPIRED: 1 });
    assert.equal(summary.networkErrors, 0);

    const rows = readFileSync(join(out, "tokens.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
    const admitted = rows.filter((r) => r.last === "ADMITTED");
    assert.equal(admitted.length, 3);
    for (const r of admitted) {
      assert.equal(r.ref.length, 16);
      assert.ok(r.admittedSeenAt >= issuedAt + 1_500); // 승격(발급 1,500ms 뒤) 전에는 ADMITTED를 볼 수 없다
      assert.ok(r.polls >= 2, `조회 ${r.polls}회`); // 첫 조회(토큰 줄 100ms 뒤)는 WAITING이었다
      assert.equal(r.firstRetryAfterMs, 100);
    }
    assert.ok(!readFileSync(join(out, "tokens.jsonl"), "utf8").includes(tokens[0])); // 토큰 원문은 남기지 않는다
  } finally {
    mock.kill();
  }
});

test("본문 도중 연결이 끊기면 네트워크 오류로 세고 다시 묻는다(멈추지 않는다)", async () => {
  let n = 0;
  const srv = http.createServer((req, res) => {
    n++;
    if (n === 1) {
      // 헤더와 본문 일부만 보내고 끊는다
      res.writeHead(200, { "Content-Type": "application/json", "Content-Length": "200" });
      res.write('{"data":');
      setTimeout(() => req.socket.destroy(), 50);
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ data: { status: "ADMITTED", rank: 0, total: 0, retryAfterMs: 0 } }));
  });
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  try {
    const out = join(mkdtempSync(join(tmpdir(), "poll-hold-")), "poll");
    const hold = spawn(process.execPath, [HOLD, "--base", `http://127.0.0.1:${srv.address().port}`, "--out", out, "--hold", "20", "--min-ms", "50", "--jitter", "0"]);
    hold.stdin.end("QTOKEN t1 50\n");
    const code = await new Promise((r) => hold.on("exit", r));
    assert.equal(code, 0);
    const summary = JSON.parse(readFileSync(join(out, "summary.json"), "utf8"));
    assert.equal(summary.reason, "모든 토큰이 끝났다"); // hold(20초)까지 멈춰 있지 않았다
    assert.equal(summary.aborted, 1);
    assert.equal(summary.networkErrors, 1);
    assert.deepEqual(summary.finalStatus, { ADMITTED: 1 });
  } finally {
    srv.close();
  }
});

test("hold가 먼저 끝나도 입력이 닫힐 때까지 줄을 계속 넘긴다(앞단 k6가 닫힌 파이프에 쓰지 않게)", async () => {
  const out = join(mkdtempSync(join(tmpdir(), "poll-hold-")), "poll");
  // 응답이 없는 주소 — 폴링은 의미 없고 hold(1초)로 끝난다
  const hold = spawn(process.execPath, [HOLD, "--base", "http://127.0.0.1:9", "--out", out, "--hold", "1", "--min-ms", "60000"]);
  const exited = new Promise((r) => hold.on("exit", r)); // 먼저 끝나 버려도 놓치지 않게 바로 건다(회귀 때 무한 대기 방지)
  let stdout = "";
  hold.stdout.on("data", (b) => (stdout += b));
  hold.stdin.write("QTOKEN t1 60000\n");
  await new Promise((r) => setTimeout(r, 1_800)); // hold 끝 뒤
  hold.stdin.write("hold 뒤 k6 줄\n");
  hold.stdin.end();
  const code = await exited;
  assert.equal(code, 0);
  assert.match(stdout, /hold 뒤 k6 줄/);
  assert.equal(JSON.parse(readFileSync(join(out, "summary.json"), "utf8")).reason, "hold 시간 끝");
});

test("재사용한 keep-alive 소켓이 ECONNRESET이면 새 연결로 한 번 다시 보내고 오류로 세지 않는다", async () => {
  // 같은 소켓의 두 번째 요청이 오면 소켓을 끊는다(서버가 유휴 연결을 막 닫은 경합을 흉내).
  let polls = 0;
  const srv = http.createServer((req, res) => {
    req.socket.served = (req.socket.served ?? 0) + 1;
    if (req.socket.served >= 2) {
      req.socket.destroy();
      return;
    }
    polls++;
    const admitted = polls >= 3;
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ data: { status: admitted ? "ADMITTED" : "WAITING", rank: 1, total: 1, retryAfterMs: 50 } }));
  });
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  try {
    const out = join(mkdtempSync(join(tmpdir(), "poll-hold-")), "poll");
    const hold = spawn(process.execPath, [HOLD, "--base", `http://127.0.0.1:${srv.address().port}`, "--out", out, "--hold", "20", "--min-ms", "50", "--jitter", "0", "--max-sockets", "1"]);
    const exited = new Promise((r) => hold.on("exit", r));
    hold.stdin.end("QTOKEN t1 50\n");
    assert.equal(await exited, 0);
    const summary = JSON.parse(readFileSync(join(out, "summary.json"), "utf8"));
    assert.deepEqual(summary.finalStatus, { ADMITTED: 1 });
    assert.equal(summary.networkErrors, 0);
    assert.ok(summary.reusedSocketRetries >= 1, `재시도 ${summary.reusedSocketRetries}`);
  } finally {
    srv.close();
  }
});
