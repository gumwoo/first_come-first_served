// 메타테스트: "하네스도 검증 대상". 위반 fixture를 넣었을 때 하네스가
// 실제로 실패(exit 1)하는지 확인한다. 통과해버리면(false negative) 메타테스트 실패.

import { spawnSync } from "node:child_process";
import path from "node:path";
import url from "node:url";

const HERE = path.dirname(url.fileURLToPath(import.meta.url));

const cases = [
  { name: "be-undocumented-api", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-undocumented-api" } },
  { name: "be-layer-breach",     script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-layer-breach" } },
  { name: "be-bad-enum",         script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-bad-enum" } },
  { name: "be-hardcoded-secret", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-hardcoded-secret" } },
  { name: "be-controller-trycatch", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-controller-trycatch" } },
  { name: "be-api-pathattr",     script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-api-pathattr" } },
  { name: "fe-missing-enum",     script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-missing-enum" } },
  { name: "fe-bad-dep",          script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-bad-dep" } },
  { name: "fe-layer-breach",     script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-layer-breach" } },
];

let failed = 0;
for (const c of cases) {
  const res = spawnSync(process.execPath, [path.join(HERE, c.script)], {
    env: { ...process.env, ...c.env },
    encoding: "utf8",
  });
  // 위반 fixture이므로 exit code가 1이어야 정상
  if (res.status === 1) {
    console.log(`✓ meta: ${c.name} → 하네스가 정상적으로 실패 감지`);
  } else {
    failed++;
    console.error(`✗ meta: ${c.name} → 하네스가 위반을 못 잡음 (exit ${res.status}) [FALSE NEGATIVE]`);
  }
}

if (failed) {
  console.error(`\n메타테스트 실패: ${failed}건의 위반을 하네스가 통과시킴`);
  process.exit(1);
}
console.log("\n✓ 메타테스트 통과: 모든 위반 fixture를 하네스가 차단함");
