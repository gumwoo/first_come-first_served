// 프론트 하네스: 스택 / enum 미러 / 이벤트 구독 / API path fragment 를
// contracts/ 와 diff. 위반 시 exit 1.

import { loadYaml, walk, read, globToRe, Reporter, REPO_ROOT } from "../lib/util.mjs";
import fs from "node:fs";
import path from "node:path";

const r = new Reporter("frontend");
const WEB = process.env.HARNESS_WEB_DIR || "apps/web";

// 계약 파일 경로 해석: override 디렉터리에 있으면 그걸, 없으면 실제 contracts/로 폴백.
// (메타테스트가 깨진 계약 1개만 fixture로 두고 나머지는 실제값을 쓰게 함)
const CONTRACTS_OVERRIDE = process.env.HARNESS_CONTRACTS_DIR;
function contractPath(name) {
  if (CONTRACTS_OVERRIDE) {
    const p = path.join(REPO_ROOT, CONTRACTS_OVERRIDE, name);
    if (fs.existsSync(p)) return `${CONTRACTS_OVERRIDE}/${name}`;
  }
  return `contracts/${name}`;
}

// ---------- 1. 허용 스택 ----------
const stack = loadYaml(contractPath("allowed-stack.yaml")).frontend;
const allowed = new Set(stack.allowed);
const devAllowed = stack.dev_allowed ?? [];
const pkgPath = path.join(REPO_ROOT, WEB, "package.json");
if (fs.existsSync(pkgPath)) {
  const pkg = JSON.parse(read(pkgPath));
  const check = (deps, isDev) => {
    for (const name of Object.keys(deps ?? {})) {
      if (allowed.has(name)) continue;
      if (isDev && devAllowed.some((p) => matchDev(p, name))) continue;
      r.fail(`허용되지 않은 프론트 의존성: ${name} (allowed-stack.yaml에 추가 필요)`);
    }
  };
  check(pkg.dependencies, false);
  check(pkg.devDependencies, true);
}
function matchDev(pattern, name) {
  if (pattern.endsWith("/*")) return name.startsWith(pattern.slice(0, -1));
  return pattern === name;
}

// ---------- 2. enum 미러 일치 (contracts ↔ types/contracts.ts) ----------
const enums = loadYaml(contractPath("enums.yaml"));
const mirrorPath = path.join(REPO_ROOT, WEB, "src/types/contracts.ts");
if (fs.existsSync(mirrorPath)) {
  const src = read(mirrorPath);
  for (const [name, def] of Object.entries(enums)) {
    // backend_only: API로 노출 안 되는 서버 내부 상태(예: OutboxStatus) → FE 미러 면제.
    // 단 FE에 굳이 미러가 있으면 값 일치는 계속 검사한다(있는데 어긋나는 건 드리프트).
    const m = src.match(new RegExp(`export const ${name} = \\[([\\s\\S]*?)\\] as const`));
    if (!m) {
      if (!def.backend_only) {
        r.fail(`FE 타입 누락: ${name} (types/contracts.ts에 미러 필요)`);
      }
      continue;
    }
    const vals = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
    const missing = def.values.filter((v) => !vals.includes(v));
    const extra = vals.filter((v) => !def.values.includes(v));
    if (missing.length) r.fail(`FE ${name}: 계약에 있으나 FE에 빠진 값 → ${missing.join(", ")}`);
    if (extra.length) r.fail(`FE ${name}: FE에 있으나 계약에 없는 값 → ${extra.join(", ")}`);
  }
}

// ---------- 3. 이벤트 구독 일치 ----------
const events = loadYaml(contractPath("events.yaml"));
const publishes = new Set(events.publishes);
// publishes ⊇ fe_subscribes 불변식 (존재하지 않는 이벤트 구독 방지)
for (const ev of events.fe_subscribes) {
  if (!publishes.has(ev)) {
    r.fail(`이벤트 계약 위반: FE 구독 '${ev}'를 BE가 발행하지 않음(events.yaml publishes)`);
  }
}
// fe_subscribes ⊇ required_fe_subscribes (필수 실시간 이벤트 구독 누락 방지)
const feSubsSet = new Set(events.fe_subscribes);
for (const ev of events.required_fe_subscribes ?? []) {
  if (!feSubsSet.has(ev)) {
    r.fail(`필수 이벤트 구독 누락: '${ev}'는 실시간 UI에 필수인데 fe_subscribes에 없음`);
  }
}
// FE 코드의 SUBSCRIBED_EVENTS가 계약 fe_subscribes와 일치
if (fs.existsSync(mirrorPath)) {
  const src = read(mirrorPath);
  const m = src.match(/SUBSCRIBED_EVENTS\s*=\s*\[([\s\S]*?)\]\s*as const/);
  if (m) {
    const subs = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
    const missing = events.fe_subscribes.filter((e) => !subs.includes(e));
    const extra = subs.filter((e) => !events.fe_subscribes.includes(e));
    if (missing.length) r.fail(`FE 구독 누락: ${missing.join(", ")}`);
    if (extra.length) r.fail(`FE 구독에 계약 없는 이벤트: ${extra.join(", ")}`);
  }
}

// ---------- 4. API path fragment drift ----------
// features/*/api/*.ts 에서 호출하는 path가 계약 api.yaml의 path prefix와 매칭되는지
const apiPaths = loadYaml(contractPath("api.yaml")).endpoints.map((e) =>
  e.path.replace(/\{[^}]+\}/g, "")
);
const apiFiles = walk(WEB + "/src/features", [".ts", ".tsx"]).filter((f) =>
  f.replace(/\\/g, "/").includes("/api/")
);
for (const file of apiFiles) {
  const src = read(file);
  for (const m of src.matchAll(/["'`](\/(?:api\/)?[a-zA-Z0-9/_-]+)["'`]/g)) {
    let p = m[1].replace(/^\/api/, "");
    if (p === "" || p === "/") continue;
    const known = apiPaths.some((cp) => p.startsWith(cp.replace(/\/+$/, "")) || cp.startsWith(p));
    if (!known) {
      r.fail(`계약에 없는 API path 호출: ${p} in ${path.relative(REPO_ROOT, file)}`);
    }
  }
}

// ---------- 5. 계층 import 위반 (layer-rules.yaml frontend) ----------
const feLayer = loadYaml(contractPath("layer-rules.yaml")).frontend;
const srcRoot = WEB + "/src";
const tsFiles = walk(srcRoot, [".ts", ".tsx"]);
for (const rule of feLayer.forbidden_imports ?? []) {
  // from은 src 기준 경로라 시작 위치에 앵커링(예: "components/**"가 features/*/components를
  // 잘못 매칭하지 않도록). 최상위 디렉터리 규칙만 적용.
  const fromRe = new RegExp("^" + globToRe(rule.from).source);
  const impRe = globToRe(rule.not_import);
  for (const file of tsFiles) {
    // src 기준 상대경로(예: components/ui/button.tsx)로 from 매칭
    const relToSrc = path
      .relative(path.join(REPO_ROOT, srcRoot), file)
      .replace(/\\/g, "/");
    if (!fromRe.test(relToSrc)) continue;
    const src = read(file);
    // import ... from "X" / import "X" 의 모듈 경로(@/ alias는 src 기준으로 정규화)
    for (const im of src.matchAll(/import\s+(?:[\s\S]*?\s+from\s+)?["']([^"']+)["']/g)) {
      const mod = im[1].replace(/^@\//, "").replace(/^\.\.?\//, "");
      if (impRe.test(mod)) {
        r.fail(`FE 계층 위반: ${relToSrc} → ${im[1]} (${rule.reason || rule.not_import})`);
      }
    }
  }
}

// ---------- 6. API 死코드 검사 (백엔드 엔드포인트를 정의만 하고 미사용) ----------
// features/*/api 의 export 함수가 그 파일 밖 어디서도 안 쓰이면 실패.
// 의도적 미사용(차기 슬라이스용)은 ALLOW에 등록.
const DEAD_API_ALLOW = new Set([]);
const apiFnFiles = walk(WEB + "/src/features", [".ts", ".tsx"]).filter((f) =>
  f.replace(/\\/g, "/").includes("/api/")
);
const allSrcFiles = walk(WEB + "/src", [".ts", ".tsx"]);
for (const file of apiFnFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const src = read(file);
  const exported = [...src.matchAll(/export\s+const\s+(\w+)\s*=/g)].map((m) => m[1]);
  for (const name of exported) {
    if (DEAD_API_ALLOW.has(name)) continue;
    const usedElsewhere = allSrcFiles.some(
      (f) => f !== file && new RegExp(`\\b${name}\\b`).test(read(f))
    );
    if (!usedElsewhere) {
      r.fail(`미사용 API 함수(死코드): ${name} (${rel}) — 호출처 없음. 쓰거나 제거/ALLOW 등록`);
    }
  }
}

// ---------- 7. SSE 훅은 구독 공백 복구 경로를 가져야 함 ----------
// SSE 이벤트는 재전송되지 않는다. 구독하지 않은 구간(최초 조회~구독 성립, 끊김~재연결)에
// 지나간 이벤트는 사라지므로, 구독이 성립할 때 현재 상태를 다시 읽거나(onopen) 주기적으로
// 폴링해야 한다. 둘 다 없으면 화면이 낡은 채로 고정된다 — 종료 상태 이벤트(order.paid 등)는
// 다시 오지 않아 영구 고착이다(TS-012).
//
// typecheck·lint·build가 전혀 못 잡는 무증상 결함이라 정적으로 막는다.
// 의도적으로 복구 경로가 필요 없다면(예: 1회성 알림 전용) 파일에 사유와 함께 예외 주석:
//   // harness:allow-sse-no-resync: 단발 토스트 전용, 상태를 들고 있지 않음
const ALLOW_SSE_RE = /\/\/\s*harness:allow-sse-no-resync\s*:?\s*(\S.*)?/;
for (const file of tsFiles) {
  const src = read(file);
  if (!/new\s+EventSource\s*\(/.test(src)) continue;
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const allow = src.match(ALLOW_SSE_RE);
  const hasResync = /\.onopen\s*=|addEventListener\(\s*["']open["']/.test(src);
  const hasPolling = /setInterval\s*\(/.test(src);

  if (hasResync || hasPolling) {
    if (allow) r.fail(`불필요한 예외 주석: ${rel} — 복구 경로가 있는데 allow-sse-no-resync가 달려 있음`);
    continue;
  }
  if (!allow) {
    r.fail(
      `SSE 복구 경로 없음: ${rel} — onopen 재조회 또는 폴링 중 하나는 필수. ` +
        `구독 공백에 지나간 이벤트는 다시 오지 않아 화면이 고착된다(TS-012)`
    );
  } else if (!allow[1]) {
    r.fail(`예외 사유 누락: ${rel} — "// harness:allow-sse-no-resync: <사유>" 형식으로 근거를 적을 것`);
  }
}

// ---------- 8. 브라우저가 직접 치는 백엔드 경로를 프론트가 프록시하는가 ----------
// 브라우저는 항상 같은 오리진으로 요청하고 Next가 백엔드로 프록시한다(next.config.mjs).
// 그런데 **브라우저가 XHR이 아니라 전체 이동으로 도달하는 경로**가 있다 — OAuth가 그렇다.
//
// 실제로 시작 경로(/oauth2/*)만 프록시하고 콜백(/login/oauth2/code/*)을 빠뜨려 배포에서
// 404가 났다(TS-017). 로컬에서는 Next(3000)와 Spring(8080)이 같은 머신이라 드러나지 않는다 —
// **배포해야만 보이는 유형**이라 정적으로 잡을 가치가 크다.
const nextConfigPath = path.join(REPO_ROOT, WEB, "next.config.mjs");
const apiYmlPath = path.join(REPO_ROOT, process.env.HARNESS_API_DIR || "apps/api",
  "src/main/resources/application.yml");

if (fs.existsSync(nextConfigPath) && fs.existsSync(apiYmlPath)) {
  const nextConfig = read(nextConfigPath);
  const apiYml = read(apiYmlPath);

  // OAuth2 클라이언트 등록이 있으면 시작·콜백 **양쪽** 경로가 프록시돼야 한다.
  const hasOauth = /oauth2:\s*[\s\S]*?client:/.test(apiYml) || /registration:/.test(apiYml);
  if (hasOauth) {
    for (const [label, needle] of [
      ["시작", "/oauth2/"],
      ["콜백", "/login/oauth2/"],
    ]) {
      if (nextConfig.includes(`source: "${needle}`)) continue;
      r.fail(
        `OAuth ${label} 경로 프록시 누락: ${WEB}/next.config.mjs에 "${needle}:path*" rewrite가 없다. ` +
          `브라우저가 전체 이동으로 도달하는 경로라 Next가 받아 404가 된다(TS-017)`
      );
    }
  }
}

r.done();
