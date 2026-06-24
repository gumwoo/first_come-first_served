// 프론트 하네스: 스택 / enum 미러 / 이벤트 구독 / API path fragment 를
// contracts/ 와 diff. 위반 시 exit 1.

import { loadYaml, walk, read, globToRe, Reporter, REPO_ROOT } from "../lib/util.mjs";
import fs from "node:fs";
import path from "node:path";

const r = new Reporter("frontend");
const WEB = process.env.HARNESS_WEB_DIR || "apps/web";

// ---------- 1. 허용 스택 ----------
const stack = loadYaml("contracts/allowed-stack.yaml").frontend;
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
const enums = loadYaml("contracts/enums.yaml");
const mirrorPath = path.join(REPO_ROOT, WEB, "src/types/contracts.ts");
if (fs.existsSync(mirrorPath)) {
  const src = read(mirrorPath);
  for (const [name, def] of Object.entries(enums)) {
    const m = src.match(new RegExp(`export const ${name} = \\[([\\s\\S]*?)\\] as const`));
    if (!m) {
      r.fail(`FE 타입 누락: ${name} (types/contracts.ts에 미러 필요)`);
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
const events = loadYaml("contracts/events.yaml");
const publishes = new Set(events.publishes);
// publishes ⊇ fe_subscribes 불변식
for (const ev of events.fe_subscribes) {
  if (!publishes.has(ev)) {
    r.fail(`이벤트 계약 위반: FE 구독 '${ev}'를 BE가 발행하지 않음(events.yaml publishes)`);
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
const apiPaths = loadYaml("contracts/api.yaml").endpoints.map((e) =>
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
const feLayer = loadYaml("contracts/layer-rules.yaml").frontend;
const srcRoot = WEB + "/src";
const tsFiles = walk(srcRoot, [".ts", ".tsx"]);
for (const rule of feLayer.forbidden_imports ?? []) {
  const fromRe = globToRe(rule.from);
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

r.done();
