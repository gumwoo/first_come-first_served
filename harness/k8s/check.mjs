// k8s 하네스: 배포 매니페스트가 **애플리케이션 코드와 실제로 맞는지** 검사한다.
//
// 왜 필요한가: 백엔드·프론트 하네스는 있는데 k8s/는 아무도 보지 않았다. 그래서 매니페스트
// 초안이 두 가지를 틀린 채 리뷰까지 갔다.
//   1) 존재하지 않는 환경변수(NEXT_PUBLIC_API_BASE_URL)를 주입 — 앱은 API_ORIGIN을 읽는다
//   2) ALB에서 /api를 API Service로 직결 — Spring에는 /api 접두어가 없어 전부 404
// 둘 다 apply 전에는 아무 증상이 없고, apply하면 조용히 깨진다. 정적으로만 잡을 수 있다.

import fs from "node:fs";
import path from "node:path";
import { walk, read, Reporter, REPO_ROOT } from "../lib/util.mjs";

const r = new Reporter("k8s");
const K8S = process.env.HARNESS_K8S_DIR || "k8s";
const API = process.env.HARNESS_API_DIR || "apps/api";
const WEB = process.env.HARNESS_WEB_DIR || "apps/web";

const manifests = walk(K8S, [".yaml", ".yml"]);
if (manifests.length === 0) {
  r.fail(`매니페스트를 하나도 못 찾았다: ${K8S}/ — 규칙이 무력화된 상태`);
  r.done();
}

// ---------- 앱이 실제로 읽는 환경변수 수집 ----------
// 백엔드: application*.yml 의 ${VAR:...} / 프론트: process.env.X
const appEnv = new Set();
for (const f of walk(API + "/src/main/resources", [".yml", ".yaml"])) {
  for (const m of read(f).matchAll(/\$\{([A-Z0-9_]+)[:}]/g)) appEnv.add(m[1]);
}
for (const f of [...walk(WEB + "/src", [".ts", ".tsx"]), ...walk(WEB, [".mjs"])]) {
  for (const m of read(f).matchAll(/process\.env\.([A-Z0-9_]+)/g)) appEnv.add(m[1]);
}

// K8s가 자동으로 넣는 것 / 런타임 표준 변수는 앱 소스에 없어도 정상이다.
const ENV_ALLOWLIST = new Set(["TZ", "JAVA_OPTS", "NODE_ENV", "PORT", "HOSTNAME"]);

if (appEnv.size === 0) {
  r.fail("앱이 읽는 환경변수를 하나도 못 읽었다 — 규칙이 무력화된 상태");
}

// ---------- 매니페스트에서 주입하는 이름 수집 ----------
// YAML 파서를 쓰지 않는다(하네스 의존성 최소화). 검사 대상이 "이름"뿐이라 줄 단위로 충분하다.
for (const file of manifests) {
  const rel = path.relative(REPO_ROOT, file);
  const raw = read(file);

  // ① ConfigMap data 키 / container env name 이 앱에 존재하는가
  const injected = new Set();
  const isConfigMap = /\bkind:\s*ConfigMap\b/.test(raw);
  for (const m of raw.matchAll(/^\s*-?\s*name:\s*([A-Z][A-Z0-9_]{2,})\s*$/gm)) injected.add(m[1]);
  if (isConfigMap) {
    const body = raw.slice(raw.indexOf("data:"));
    for (const m of body.matchAll(/^\s{2,}([A-Z][A-Z0-9_]{2,}):/gm)) injected.add(m[1]);
  }
  for (const name of injected) {
    if (appEnv.has(name) || ENV_ALLOWLIST.has(name)) continue;
    r.fail(
      `앱이 읽지 않는 환경변수: ${rel} → ${name}. ` +
        `application.yml의 \${${name}} 이나 process.env.${name} 이 없다 — 이름 오타이거나 죽은 설정이다`
    );
  }

  // ② 공개 Ingress가 API Service로 직결하는가
  //
  // 앱은 Next가 /api·/oauth2를 프록시하는 구조다(next.config.mjs rewrites). ALB의 Prefix
  // 라우팅은 접두어를 제거하지 않으므로, ALB가 /api를 API로 직접 보내면 Spring이
  // "/api/auth/login"을 받고 그런 매핑이 없어 전부 404가 된다.
  if (/\bkind:\s*Ingress\b/.test(raw) && /name:\s*flowticket-api\b/.test(raw)) {
    r.fail(
      `Ingress가 API Service로 직결: ${rel}. ALB는 web만 보고, /api·/oauth2는 Next rewrite가 ` +
        `프록시해야 한다(ALB Prefix 라우팅은 접두어를 제거하지 않는다 → Spring에서 404)`
    );
  }

  // ③ 공개 Ingress에 /actuator 경로를 열지 않는다
  // exposure에 metrics·prometheus가 포함돼 있어 인터넷에 관측 데이터가 열린다.
  // ALB 헬스체크는 타깃그룹이 Pod IP로 직접 검사하므로 이 규칙은 애초에 필요 없다.
  if (/\bkind:\s*Ingress\b/.test(raw) && /^\s*-?\s*path:\s*\/actuator/m.test(raw)) {
    r.fail(`Ingress에 /actuator 공개 경로: ${rel} — metrics·prometheus가 외부로 열린다`);
  }
}

// ---------- ④ 빌드 시점에 굳는 값을 런타임 env로 주입하지 않는가 ----------
// Next의 rewrites()는 standalone 번들로 구워져 **런타임 env로 바뀌지 않는다.** 이 프로젝트가
// 실제로 겪었다 — apps/web/Dockerfile 주석: "런타임 주입을 시도했더니 빌드 때 값인
// localhost:8080으로 프록시해 ECONNREFUSED가 났다". 그래서 build-arg로 옮겼다.
// 매니페스트에 다시 넣으면 "설정한 것처럼 보이지만 아무 효과가 없는" 죽은 값이 된다.
const BUILD_TIME_ONLY = ["API_ORIGIN"];
for (const file of manifests) {
  const raw = read(file);
  const rel = path.relative(REPO_ROOT, file);
  for (const name of BUILD_TIME_ONLY) {
    const re = new RegExp("^\\s*-?\\s*name:\\s*" + name + "\\s*$", "m");
    if (re.test(raw)) {
      r.fail(
        `빌드 시점 값을 런타임 env로 주입: ${rel} → ${name}. ` +
          `Next rewrites는 standalone 번들로 구워져 런타임에 바뀌지 않는다 — ` +
          `.github/workflows/image.yml의 build-args에서 정한다`
      );
    }
  }
}

// ---------- ⑤ 이미지 build-arg의 API 주소가 Service가 여는 포트와 맞는가 ----------
// Service는 port(클라이언트가 붙는 포트)와 targetPort(Pod로 넘기는 포트)가 다르다.
// targetPort를 URL에 적으면 Service가 열지 않은 포트라 연결이 거부된다 — 초안이 :8080이었다.
const svcPorts = new Map();
for (const file of manifests) {
  for (const block of read(file).split(/^---$/m)) {
    if (!/\bkind:\s*Service\b/.test(block)) continue;
    const nm = block.match(/^\s*name:\s*(\S+)/m);
    const pt = block.match(/^\s*-?\s*port:\s*([0-9]+)/m);
    if (nm && pt) svcPorts.set(nm[1], pt[1]);
  }
}
const imageWorkflow = path.join(REPO_ROOT, ".github/workflows/image.yml");
if (fs.existsSync(imageWorkflow)) {
  const re = /API_ORIGIN=[^\n]*?http:\/\/([a-z0-9-]+)(?::([0-9]+))?/g;
  for (const m of read(imageWorkflow).matchAll(re)) {
    const host = m[1];
    const port = m[2];

    // 로컬·compose용 값은 클러스터 Service가 아니다.
    if (host === "localhost" || host === "api") continue;

    // 포트를 안 붙였다고 통과시키면 안 된다 — 오타난 Service 이름은 포트가 없어도 못 붙는다.
    // 초안의 규칙이 딱 이 구멍을 갖고 있었다(포트가 틀린 경우만 잡았다).
    if (!svcPorts.has(host)) {
      r.fail(
        `image.yml의 API_ORIGIN이 존재하지 않는 Service를 가리킨다: http://${host} — ` +
          `${K8S}/에 그런 이름의 Service가 없다(알고 있는 것: ${[...svcPorts.keys()].join(", ") || "없음"})`
      );
      continue;
    }
    const expected = svcPorts.get(host);
    if (port && port !== expected) {
      r.fail(
        `image.yml의 API_ORIGIN 포트가 Service와 불일치: http://${host}:${port} — ` +
          `Service ${host}는 ${expected}만 연다(targetPort는 클라이언트가 붙는 포트가 아니다)`
      );
    }
  }
}

r.done();
