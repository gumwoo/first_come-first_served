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
  { name: "be-naive-datetime",   script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-naive-datetime" } },
  { name: "be-unvalidated-paging", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-unvalidated-paging" } },
  { name: "be-db-conn-ceiling",  script: "backend/check.mjs", expect: "DB 커넥션 상한 초과:", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-db-conn-ceiling" } },
  { name: "be-db-conn-surge",    script: "backend/check.mjs", expect: "maxSurge 5", env: { HARNESS_K8S_DIR: "harness/fixtures/violations/be-db-conn-surge" } },
  { name: "be-enum-ordinal",     script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-enum-ordinal" } },
  { name: "be-entity-data",      script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-entity-data" } },
  { name: "be-field-injection",  script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-field-injection" } },
  { name: "be-tx-private",       script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-tx-private" } },
  { name: "be-open-security",    script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-open-security" } },
  { name: "be-flyway-dup-version", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-flyway-dup-version" } },
  { name: "be-event-not-broadcast", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-event-not-broadcast" } },
  { name: "be-unguarded-status-update", script: "backend/check.mjs", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-unguarded-status-update" } },
  { name: "be-destructive-ddl",  script: "backend/check.mjs", expect: "파괴적 DDL:", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-destructive-ddl" } },
  { name: "be-dangling-doc-ref", script: "backend/check.mjs", expect: "끊어진 문서 참조:", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-dangling-doc-ref" } },
  { name: "be-http-no-timeout", script: "backend/check.mjs", expect: "외부 HTTP 클라이언트에 타임아웃이 없다:", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-http-no-timeout" } },
  { name: "be-http-no-timeout-injected", script: "backend/check.mjs", expect: "외부 HTTP 클라이언트에 타임아웃이 없다:", env: { HARNESS_API_DIR: "harness/fixtures/violations/be-http-no-timeout-injected" } },
  // k8s: 매니페스트가 애플리케이션 코드와 어긋나는 두 경로(둘 다 apply 전에는 무증상)
  { name: "k8s-unknown-env", script: "k8s/check.mjs", expect: "앱이 읽지 않는 환경변수:", env: { HARNESS_K8S_DIR: "harness/fixtures/violations/k8s-unknown-env" } },
  { name: "k8s-ingress-api-direct", script: "k8s/check.mjs", expect: "Ingress가 API Service로 직결:", env: { HARNESS_K8S_DIR: "harness/fixtures/violations/k8s-ingress-api-direct" } },
  { name: "k8s-buildtime-env", script: "k8s/check.mjs", expect: "빌드 시점 값을 런타임 env로 주입:", env: { HARNESS_K8S_DIR: "harness/fixtures/violations/k8s-buildtime-env" } },
  { name: "k8s-hpa-replicas", script: "k8s/check.mjs", expect: "HPA가 소유하는 Deployment에 replicas가 있다:", env: { HARNESS_K8S_DIR: "harness/fixtures/violations/k8s-hpa-replicas" } },
  { name: "k8s-no-tz-pin", script: "k8s/check.mjs", expect: "TZ가 고정돼 있지 않다:", env: { HARNESS_K8S_DIR: "harness/fixtures/violations/k8s-no-tz-pin" } },
  { name: "fe-missing-enum",     script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-missing-enum" } },
  { name: "fe-bad-dep",          script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-bad-dep" } },
  { name: "fe-layer-breach",     script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-layer-breach" } },
  { name: "fe-dead-api",         script: "frontend/check.mjs", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-dead-api" } },
  { name: "fe-sse-no-resync",    script: "frontend/check.mjs", expect: "SSE 복구 경로 없음:", env: { HARNESS_WEB_DIR: "harness/fixtures/violations/fe-sse-no-resync" } },
  // ② 필수 이벤트 구독 누락 (계약/웹 둘 다 override해 단독 격리)
  { name: "fe-missing-required-event", script: "frontend/check.mjs", env: {
    HARNESS_CONTRACTS_DIR: "harness/fixtures/violations/fe-missing-required-event",
    HARNESS_WEB_DIR: "harness/fixtures/violations/fe-missing-required-event/web" } },
  // docs: 문서가 저장소의 현재 상태를 반대로 설명하는 두 경로(둘 다 코드는 멀쩡해 CI가 통과한다)
  { name: "docs-absent-but-exists", script: "docs/check.mjs", expect: "없다고 단언한 대상이 실존한다:", env: {
    HARNESS_DOCS_DIR: "harness/fixtures/violations/docs-absent-but-exists",
    HARNESS_DOCS_EXTRA: "harness/fixtures/violations/docs-absent-but-exists" } },
  { name: "docs-status-drift", script: "docs/check.mjs", expect: "문서 상태가 어긋난다:", env: {
    HARNESS_DOCS_DIR: "harness/fixtures/violations/docs-status-drift",
    HARNESS_DOCS_EXTRA: "harness/fixtures/violations/docs-status-drift" } },
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
  if (res.status !== 1) {
    failed++;
    console.error(`✗ meta: ${c.name} → 하네스가 위반을 못 잡음 (exit ${res.status}) [FALSE NEGATIVE]`);
    continue;
  }
  // exit 1만으로는 "의도한 규칙이 잡았는지"를 알 수 없다 — fixture가 엉뚱한 규칙에 걸려도
  // 통과한 것처럼 보인다. expect가 있는 케이스는 메시지까지 확인한다.
  // (기존 케이스에는 아직 없다. 하나씩 붙여 나간다.)
  if (c.expect && !`${res.stdout ?? ""}${res.stderr ?? ""}`.includes(c.expect)) {
    failed++;
    console.error(`✗ meta: ${c.name} → 실패는 했으나 의도한 규칙이 아님 (기대 메시지: "${c.expect}")`);
    continue;
  }
  console.log(`✓ meta: ${c.name} → 하네스가 정상적으로 실패 감지`);
}

if (failed) {
  console.error(`\n메타테스트 실패: ${failed}건의 위반을 하네스가 통과시킴`);
  process.exit(1);
}
console.log("\n✓ 메타테스트 통과: 모든 위반 fixture를 하네스가 차단함");
