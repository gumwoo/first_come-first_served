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
  //
  // ⚠️ 파일 전체에서 "kind: Ingress"와 "name: flowticket-api"를 따로 찾으면 오탐이 난다 —
  // 오버레이 kustomization은 patch target(kind: Ingress)과 images(name: flowticket-api)를
  // 한 파일에 갖는다. 실제로 그렇게 걸렸다. **backend 블록 안에서** 함께 나올 때만 위반이다.
  const backendRe = /backend:\s*(?:\r?\n\s+|\{\s*)service:\s*(?:\r?\n\s+|\{\s*)name:\s*(\S+?)[\s,}]/g;
  if (/\bkind:\s*Ingress\b/.test(raw)) {
    for (const m of raw.matchAll(backendRe)) {
      if (m[1].replace(/["']/g, "") !== "flowticket-api") continue;
      r.fail(
        `Ingress가 API Service로 직결: ${rel}. ALB는 web만 보고, /api·/oauth2는 Next rewrite가 ` +
          `프록시해야 한다(ALB Prefix 라우팅은 접두어를 제거하지 않는다 → Spring에서 404)`
      );
    }
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

// ---------- ⑥ 브라우저 번들에 구워지는 값(NEXT_PUBLIC_*)이 빌드 인자로 준비돼 있는가 ----------
// 규칙 ④의 반대편이다. ④는 "빌드 시점 값을 런타임 env로 넣는 것"을 막고, ⑥은
// **빌드 시점 값이 아예 빠진 것**을 막는다. 실제로 NEXT_PUBLIC_TOSS_CLIENT_KEY가 그랬다 —
// 코드는 읽는데 Dockerfile에 ARG가 없어 이미지에 값이 안 들어갔다.
//
// 이 유형이 위험한 이유: **에러가 아니라 다른 흐름으로 빠진다.** 결제창이 안 뜨고 조용히
// 다른 경로를 타므로 배포 후에도 눈치채기 어렵다.
const webDockerfile = path.join(REPO_ROOT, WEB, "Dockerfile");
if (fs.existsSync(webDockerfile)) {
  const dockerfile = read(webDockerfile);
  const used = new Set();
  for (const f of walk(WEB + "/src", [".ts", ".tsx"])) {
    for (const m of read(f).matchAll(/process\.env\.(NEXT_PUBLIC_[A-Z0-9_]+)/g)) used.add(m[1]);
  }
  for (const name of used) {
    if (new RegExp("^\\s*ARG\\s+" + name + "\\b", "m").test(dockerfile)) continue;
    r.fail(
      `브라우저 빌드 값에 ARG 누락: ${name} — 코드가 읽는데 ${WEB}/Dockerfile에 ARG가 없다. ` +
        `NEXT_PUBLIC_*는 번들에 구워져 런타임 주입이 불가능하다(빈 값이면 에러 없이 다른 흐름으로 빠진다)`
    );
  }
}

// ---------- ⑦ API 컨테이너의 타임존이 UTC로 고정돼 있는가 ----------
// 이 프로젝트의 시각 데이터는 "DB의 벽시계 = 컨테이너 존"을 전제로 한다. 엔티티가
// LocalDateTime.now()(시스템 존)로 값을 만들고, 응답도 같은 존으로 오프셋을 붙인다(JacksonConfig).
//
// 그래서 컨테이너 존이 바뀌면 **이미 저장된 행의 절대 시각이 통째로 이동한다.** 지금 DB에는
// UTC 벽시계가 쌓여 있으므로 Asia/Seoul로 바꾸면 기존 예매의 결제 기한이 9시간 어긋난다.
//
// 배포 파일에 값을 적어두는 것만으로는 부족하다 — 지워져도 아무 증상이 없고, 그 다음 배포부터
// 조용히 어긋나기 시작한다(오프셋 누락으로 좌석 선점이 즉시 만료된 사건과 같은 유형).
// 그래서 규칙으로 못박는다. 존을 정말 바꾸려면 Instant/timestamptz 전환이 선행돼야 한다.
const apiDeploy = manifests.find((f) => {
  const raw = read(f);
  return /\bkind:\s*Deployment\b/.test(raw) && /name:\s*flowticket-api\b/.test(raw);
});
if (!apiDeploy) {
  r.fail("flowticket-api Deployment를 못 찾았다 — 규칙 ⑦이 무력화된 상태");
} else {
  const raw = read(apiDeploy);
  const rel = path.relative(REPO_ROOT, apiDeploy);
  // `- name: TZ` 바로 뒤의 value를 본다(줄 단위 파싱 — 규칙 ①과 같은 방식).
  const tz = raw.match(/^\s*-\s*name:\s*TZ\s*$\r?\n\s*value:\s*["']?([A-Za-z0-9_/+-]+)["']?/m);
  if (!tz) {
    r.fail(
      `API 컨테이너에 TZ가 고정돼 있지 않다: ${rel}. ` +
        `엔티티는 LocalDateTime.now()(시스템 존)로 시각을 만들고 응답도 같은 존으로 오프셋을 ` +
        `붙인다 — 존이 흔들리면 이미 저장된 행의 절대 시각이 이동한다. env에 {name: TZ, value: UTC} 필요`
    );
  } else if (tz[1] !== "UTC") {
    r.fail(
      `API 컨테이너 TZ가 UTC가 아니다: ${rel} → ${tz[1]}. ` +
        `DB에는 UTC 벽시계가 쌓여 있어 존을 바꾸면 기존 행이 그 시차만큼 어긋난다 — ` +
        `Instant/timestamptz 전환을 Expand-Contract로 먼저 해야 한다`
    );
  }
}

r.done();
