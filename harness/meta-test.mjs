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
  { name: "be-bad-errorcode",    script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-bad-errorcode" } },
  { name: "be-hardcoded-secret", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-hardcoded-secret" } },
  { name: "be-controller-trycatch", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-controller-trycatch" } },
  { name: "be-api-pathattr",     script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-api-pathattr" } },
  { name: "be-yml-secret",       script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-yml-secret" } },
  { name: "be-table-no-doc",     script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-table-no-doc" } },
  { name: "be-jwt-no-type",      script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-jwt-no-type" } },
  { name: "be-actuator-open",    script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-actuator-open" } },
  { name: "fe-missing-enum",     script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-missing-enum" } },
  { name: "fe-bad-dep",          script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-bad-dep" } },
  { name: "fe-layer-breach",     script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-layer-breach" } },
  { name: "fe-dead-api",         script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-dead-api" } },
  // ② 필수 이벤트 구독 누락 (계약/웹 둘 다 override해 단독 격리)
  { name: "fe-missing-required-event", script: "frontend/check.mjs", env: {
    HARNESS_CONTRACTS_DIR: "harness/fixtures/violations/fe-missing-required-event",
    HARNESS_WEB_DIR: "harness/fixtures/violations/fe-missing-required-event/web" } },
  // ③ 계약 스키마 위반 (깨진 api.yaml만 override, 나머지는 실제 폴백)
  { name: "contract-bad-schema", script: "schema-check.mjs", env: {
    HARNESS_CONTRACTS_DIR: "harness/fixtures/violations/contract-bad-schema" } },
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
