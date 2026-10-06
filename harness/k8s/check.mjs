// k8s 하네스: 배포 매니페스트가 애플리케이션 코드와 맞는지 검사한다.
//
// 매니페스트는 앱과 따로 작성돼 이런 불일치가 생긴다.
//   1) 앱이 읽지 않는 환경변수(예: NEXT_PUBLIC_API_BASE_URL) 주입. 앱은 API_ORIGIN을 읽는다
//   2) ALB에서 /api를 API Service로 직결. Spring에는 /api 접두어가 없어 전부 404
// 둘 다 apply 전에는 증상이 없어 정적으로 잡는다.

import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { walk, read, Reporter, REPO_ROOT } from "../lib/util.mjs";

const r = new Reporter("k8s");
const K8S = process.env.HARNESS_K8S_DIR || "k8s";
const API = process.env.HARNESS_API_DIR || "apps/api";
const WEB = process.env.HARNESS_WEB_DIR || "apps/web";

const manifests = walk(K8S, [".yaml", ".yml"]);
if (manifests.length === 0) {
  r.fail(`매니페스트를 하나도 못 찾았다: ${K8S}/: 규칙이 무력화된 상태`);
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
  r.fail("앱이 읽는 환경변수를 하나도 못 읽었다. 규칙이 무력화된 상태");
}

// ---------- 매니페스트에서 주입하는 이름 수집 ----------
// YAML 파서를 쓰지 않는다(하네스 의존성 최소화). 검사 대상이 "이름"뿐이라 줄 단위로 충분하다.
for (const file of manifests) {
  const rel = path.relative(REPO_ROOT, file);
  const raw = read(file);

  // 1) ConfigMap data 키 / container env name 이 앱에 존재하는가
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
        `application.yml의 \${${name}} 이나 process.env.${name} 이 없다. 이름 오타이거나 죽은 설정이다`
    );
  }

  // 2) 공개 Ingress의 API Service 직결 — 아래 ⑩ 다음의 구조 검사(YAML 파싱)로 옮겼다(ADR-024).

  // 3) 공개 Ingress에 /actuator 경로를 열지 않는다
  // exposure에 metrics·prometheus가 포함돼 있어 인터넷에 관측 데이터가 열린다.
  // ALB 헬스체크는 타깃그룹이 Pod IP로 직접 검사하므로 이 규칙은 애초에 필요 없다.
  if (/\bkind:\s*Ingress\b/.test(raw) && /^\s*-?\s*path:\s*\/actuator/m.test(raw)) {
    r.fail(`Ingress에 /actuator 공개 경로: ${rel}: metrics·prometheus가 외부로 열린다`);
  }
}

// ---------- 4) 빌드 시점에 굳는 값을 런타임 env로 주입하지 않는가 ----------
// Next의 rewrites()는 standalone 번들로 구워져 런타임 env로 바뀌지 않는다(apps/web/Dockerfile:
// 그래서 build-arg로 정한다). 매니페스트에 다시 넣으면 "설정한 것처럼 보이지만 아무 효과가 없는" 죽은 값이 된다.
const BUILD_TIME_ONLY = ["API_ORIGIN"];
for (const file of manifests) {
  const raw = read(file);
  const rel = path.relative(REPO_ROOT, file);
  for (const name of BUILD_TIME_ONLY) {
    const re = new RegExp("^\\s*-?\\s*name:\\s*" + name + "\\s*$", "m");
    if (re.test(raw)) {
      r.fail(
        `빌드 시점 값을 런타임 env로 주입: ${rel} → ${name}. ` +
          `Next rewrites는 standalone 번들로 구워져 런타임에 바뀌지 않는다. ` +
          `.github/workflows/image.yml의 build-args에서 정한다`
      );
    }
  }
}

// ---------- 5) 이미지 build-arg의 API 주소가 Service가 여는 포트와 맞는가 ----------
// Service는 port(클라이언트가 붙는 포트)와 targetPort(Pod로 넘기는 포트)가 다르다.
// targetPort를 URL에 적으면 Service가 열지 않은 포트라 연결이 거부된다(예: :8080).
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

    // 포트를 안 붙였다고 통과시키면 안 된다. 오타난 Service 이름은 포트가 없어도 못 붙는다.
    // 포트가 틀린 경우만 잡으면 이 구멍이 남는다.
    if (!svcPorts.has(host)) {
      r.fail(
        `image.yml의 API_ORIGIN이 존재하지 않는 Service를 가리킨다: http://${host}: ` +
          `${K8S}/에 그런 이름의 Service가 없다(알고 있는 것: ${[...svcPorts.keys()].join(", ") || "없음"})`
      );
      continue;
    }
    const expected = svcPorts.get(host);
    if (port && port !== expected) {
      r.fail(
        `image.yml의 API_ORIGIN 포트가 Service와 불일치: http://${host}:${port}: ` +
          `Service ${host}는 ${expected}만 연다(targetPort는 클라이언트가 붙는 포트가 아니다)`
      );
    }
  }
}

// ---------- 6) 브라우저 번들에 구워지는 값(NEXT_PUBLIC_*)이 빌드 인자로 준비돼 있는가 ----------
// 규칙 4)의 반대편이다. 4)는 빌드 시점 값을 런타임 env로 넣는 것을, 6)은 빌드 시점 값이 빠진 것을 막는다.
// 코드는 읽는데 Dockerfile에 ARG가 없으면 값이 이미지에 들어가지 않고, 에러 없이 다른 흐름으로 빠진다
// (예: 토스 키가 없으면 결제창 없는 경로).
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
      `브라우저 빌드 값에 ARG 누락: ${name}: 코드가 읽는데 ${WEB}/Dockerfile에 ARG가 없다. ` +
        `NEXT_PUBLIC_*는 번들에 구워져 런타임 주입이 불가능하다(빈 값이면 에러 없이 다른 흐름으로 빠진다)`
    );
  }
}

// ---------- 7) API 컨테이너의 타임존이 UTC로 고정돼 있는가 ----------
// 이 프로젝트의 시각 데이터는 "DB의 벽시계 = 컨테이너 존"을 전제로 한다. 엔티티가
// LocalDateTime.now()(시스템 존)로 값을 만들고, 응답도 같은 존으로 오프셋을 붙인다(JacksonConfig).
//
// 그래서 컨테이너 존이 바뀌면 이미 저장된 행의 절대 시각이 통째로 이동한다. 지금 DB에는
// UTC 벽시계가 쌓여 있으므로 Asia/Seoul로 바꾸면 기존 예매의 결제 기한이 9시간 어긋난다.
//
// 배포 파일에 값을 적어두는 것만으로는 부족하다. 지워져도 아무 증상이 없고, 그 다음 배포부터
// 어긋나기 시작한다(오프셋 누락으로 좌석 선점이 즉시 만료된 사건과 같은 유형).
// 그래서 규칙으로 못박는다. 존을 정말 바꾸려면 Instant/timestamptz 전환이 선행돼야 한다.
const apiDeploy = manifests.find((f) => {
  const raw = read(f);
  return /\bkind:\s*Deployment\b/.test(raw) && /name:\s*flowticket-api\b/.test(raw);
});
if (!apiDeploy) {
  r.fail("flowticket-api Deployment를 못 찾았다. 규칙 ⑦이 무력화된 상태");
} else {
  const raw = read(apiDeploy);
  const rel = path.relative(REPO_ROOT, apiDeploy);
  // `- name: TZ` 바로 뒤의 value를 본다(규칙 1)과 같은 줄 단위 파싱).
  const tz = raw.match(/^\s*-\s*name:\s*TZ\s*$\r?\n\s*value:\s*["']?([A-Za-z0-9_/+-]+)["']?/m);
  if (!tz) {
    r.fail(
      `API 컨테이너에 TZ가 고정돼 있지 않다: ${rel}. ` +
        `엔티티는 LocalDateTime.now()(시스템 존)로 시각을 만들고 응답도 같은 존으로 오프셋을 ` +
        `붙인다. 존이 흔들리면 이미 저장된 행의 절대 시각이 이동한다. env에 {name: TZ, value: UTC} 필요`
    );
  } else if (tz[1] !== "UTC") {
    r.fail(
      `API 컨테이너 TZ가 UTC가 아니다: ${rel} → ${tz[1]}. ` +
        `DB에는 UTC 벽시계가 쌓여 있어 존을 바꾸면 기존 행이 그 시차만큼 어긋난다. ` +
        `Instant/timestamptz 전환을 Expand-Contract로 먼저 해야 한다`
    );
  }
}

// ---------- 8) HPA가 소유하는 Deployment에 replicas를 두지 않는다 ----------
// Git에 replicas가 있으면 ArgoCD sync가 HPA가 정한 파드 수를 Git 값으로 덮어써, 부하 중 스케일아웃이 취소된다.
// RespectIgnoreDifferences로는 막히지 않았으므로(TS-022) 필드를 매니페스트에서 없애고 이 규칙으로 유지한다.
// 하한은 HPA의 minReplicas가 담당한다. HPA가 없는 Deployment(web)는 대상이 아니다.
const docs = [];
for (const f of manifests) {
  let parsed;
  try {
    parsed = yaml.loadAll(read(f));
  } catch {
    continue; // 파싱이 안 되는 파일은 다른 규칙이 본다
  }
  for (const d of parsed) if (d && typeof d === "object") docs.push({ doc: d, file: f });
}

const hpaTargets = new Set();
for (const { doc } of docs) {
  if (doc.kind !== "HorizontalPodAutoscaler") continue;
  const t = doc.spec?.scaleTargetRef;
  if (t?.kind === "Deployment" && t.name) hpaTargets.add(t.name);
}
if (hpaTargets.size === 0) {
  r.fail("HPA를 하나도 못 찾았다. 규칙 ⑧이 무력화된 상태");
}

for (const { doc, file } of docs) {
  if (doc.kind !== "Deployment") continue;
  if (!hpaTargets.has(doc.metadata?.name)) continue;
  if (doc.spec?.replicas === undefined) continue;
  r.fail(
    `HPA가 소유하는 Deployment에 replicas가 있다: ${path.relative(REPO_ROOT, file)} ` +
      `→ ${doc.metadata.name} (replicas: ${doc.spec.replicas}). ArgoCD가 sync할 때마다 HPA가 정한 ` +
      `파드 수를 이 값으로 덮어쓴다. 부하 중 스케일아웃이 취소된다. 필드를 지우고 하한은 HPA의 ` +
      `minReplicas에 맡겨라(ignoreDifferences로는 sync를 막지 못하는 것을 실측했다)`
  );
}

// ---------- 10) actuator는 관리 포트에만 두고, probe는 메인 포트에 둔다(TS-041) ----------
//
// 8080(메인 포트)은 공개 경로(ALB → web → Next rewrite /api/:path*)로 인터넷에서 닿는다. 경로 규칙으로 /actuator를
// 막으면 인코딩 변형(/api/%61ctuator/prometheus)이 그대로 넘어가 api가 디코딩해 응답했다(운영에서 200 확인).
// 그래서 actuator를 관리 포트로 분리한다. 값이 지워지면 아무 증상 없이 다시 열리므로 규칙으로 못박는다.
//
// 값은 api Deployment의 컨테이너 env에 둔다(공용 ConfigMap 금지). probe와 같은 파드 템플릿이어야 둘이 한 번에 바뀐다 —
// ConfigMap에 두면 옛 템플릿 파드가 새 값을 읽어(옛 probe 404 → 재시작 반복) 또는 새 템플릿이 옛 값을 읽어(8080에 actuator가
// 조용히 남음) 롤아웃·재시작·스케일 중에 어긋난다. Spring은 env를 자리표시자 없이도 management.server.port로 묶는다.
//
// 반대로 probe를 관리 포트로 옮기면 그 포트는 별도 톰캣이라 메인 포트 요청 스레드가 포화돼도 성공해 버린다 —
// readiness는 요청을 못 받는 파드에 트래픽을 계속 보내고, liveness는 멈춘 메인 포트를 놓친다. probe는 메인 포트의
// /livez·/readyz(management.endpoint.health.probes.add-additional-paths)를 쓴다.
for (const { doc, file } of docs) {
  if (doc.kind === "ConfigMap" && doc.data && Object.prototype.hasOwnProperty.call(doc.data, "MANAGEMENT_SERVER_PORT")) {
    r.fail(
      `관리 포트를 ConfigMap에 두었다: ${path.relative(REPO_ROOT, file)} → ${doc.metadata?.name}. ` +
        `probe와 다른 리소스라 롤아웃 중 옛 템플릿·새 값(또는 반대) 조합이 생긴다. api Deployment 컨테이너 env에 둬라(TS-041)`
    );
  }
}
const apiDeployDoc = docs.find(({ doc }) => doc.kind === "Deployment" && doc.metadata?.name === "flowticket-api");
const apiContainer = (apiDeployDoc?.doc.spec?.template?.spec?.containers ?? []).find((c) => c?.name === "api");
if (!apiContainer) {
  r.fail("flowticket-api Deployment의 api 컨테이너를 못 찾았다. 규칙 ⑩이 무력화된 상태");
} else {
  const rel = path.relative(REPO_ROOT, apiDeployDoc.file);
  const c = apiContainer;
  const envEntry = (c.env ?? []).find((e) => e?.name === "MANAGEMENT_SERVER_PORT");
  const v = String(envEntry?.value ?? "").trim();
  let mgmtPort = null;
  if (!/^\d+$/.test(v) || v === "8080") {
    r.fail(
      `actuator가 일반 API 포트에 있다: ${rel} → ${c.name}.env MANAGEMENT_SERVER_PORT=${v || "(없음/참조)"}. ` +
        `8080은 Next rewrite(/api/:path*)로 인터넷에서 닿아 경로 규칙으로는 인코딩 변형을 막지 못한다(TS-041). 관리 포트를 분리하라`
    );
  } else {
    mgmtPort = Number(v);
  }
  // probe 포트는 이름(`port: management`)으로도 쓸 수 있다 — 컨테이너 포트 이름을 번호로 푼다.
  const named = Object.fromEntries((c.ports ?? []).filter((p) => p?.name).map((p) => [p.name, Number(p.containerPort)]));
  for (const kind of ["readinessProbe", "livenessProbe", "startupProbe"]) {
    const g = c[kind]?.httpGet;
    if (!g) continue;
    const portNum = typeof g.port === "string" && !/^\d+$/.test(g.port) ? named[g.port] : Number(g.port);
    if (String(g.path ?? "").startsWith("/actuator") || (mgmtPort !== null && portNum === mgmtPort)) {
      r.fail(
        `probe가 관리 포트를 본다: ${rel} → ${c.name}.${kind} ${g.path}:${g.port}. ` +
          `관리 포트는 별도 톰캣이라 메인 포트가 포화돼도 성공한다. 메인 포트의 /livez·/readyz를 써라(TS-041)`
      );
    }
  }
}

// ---------- 2) 공개 Ingress가 API Service로 직결하는가 — ADR-024 허용 목록만 ----------
//
// 앱은 Next가 /api·/oauth2를 프록시하는 구조다(next.config.mjs rewrites). ALB 경로 라우팅은 접두어를 떼지 않으므로
// ALB가 /api를 API로 그냥 보내면 Spring이 "/api/auth/login"을 받고 404가 된다. ADR-024는 대기열 진입·상태 두 경로만
// ALB URL rewrite(transforms)로 /api를 떼어 api로 직접 보낸다. 그래서:
//   - api 직결은 허용 목록(경로 + pathType)만 — 그 밖(예: /api Prefix 전체)은 actuator·관리자 경로까지 연다
//   - api 직결이 있으면 그 Service의 url-rewrite transform이 있어야 한다 — 없으면 Spring이 /api/...를 받아 404
//   - Prefix 규칙이 허용 경로를 가리면 안 된다 — 컨트롤러가 Prefix를 ImplementationSpecific보다 앞에 두어
//     진입이 오류 없이 web으로 간다(기본 경로는 ImplementationSpecific `/*`로 맨 뒤에)
const API_DIRECT_ALLOWED = new Map([
  ["/api/queue/status", "Exact"],
  ["/api/events/*/queue/token", "ImplementationSpecific"],
]);
for (const { doc, file } of docs) {
  if (doc.kind !== "Ingress") continue;
  const rel = path.relative(REPO_ROOT, file);
  const paths = (doc.spec?.rules ?? []).flatMap((rule) => rule?.http?.paths ?? []);
  const apiPaths = paths.filter((p) => p?.backend?.service?.name === "flowticket-api");
  for (const p of apiPaths) {
    if (API_DIRECT_ALLOWED.get(p.path) !== p.pathType) {
      r.fail(
        `Ingress가 API Service로 직결: ${rel} → ${p.path} (${p.pathType}). 허용 목록(ADR-024: 대기열 진입·상태 두 경로) 밖이다. ` +
          `나머지 /api·/oauth2는 Next rewrite가 프록시해야 한다(ALB 경로 라우팅은 접두어를 떼지 않는다 → Spring 404, 넓게 열면 actuator·관리자 경로 노출)`
      );
    }
  }
  if (apiPaths.length === 0) continue;
  const t = doc.metadata?.annotations?.["alb.ingress.kubernetes.io/transforms.flowticket-api"] ?? "";
  if (!/"type"\s*:\s*"url-rewrite"/.test(t)) {
    r.fail(
      `api 직결 경로에 /api 제거 rewrite가 없다: ${rel}. alb.ingress.kubernetes.io/transforms.flowticket-api(url-rewrite)가 ` +
        `없으면 Spring이 /api/... 를 받아 404가 된다(ADR-024)`
    );
  }
  for (const p of paths) {
    if (p?.pathType !== "Prefix") continue;
    const prefix = String(p.path ?? "");
    const shadowed = apiPaths.filter((a) => a.pathType !== "Exact" && (prefix === "/" || a.path.startsWith(prefix)));
    if (shadowed.length > 0) {
      r.fail(
        `Prefix 규칙이 api 직결 규칙을 가린다: ${rel} → ${prefix} (Prefix)가 ${shadowed.map((a) => a.path).join(", ")}보다 먼저 평가된다. ` +
          `컨트롤러는 Prefix를 ImplementationSpecific보다 앞에 둬 요청이 오류 없이 다른 백엔드로 간다 — 기본 경로는 ImplementationSpecific /*로 맨 뒤에(ADR-024)`
      );
    }
  }
}

// ---------- 9) ExternalSecret이 실제 적용 경로에 연결돼 있는가 ----------
//
// ArgoCD Application은 `k8s/overlays/demo-local` 하나만 동기화한다. 그래서
// `k8s/external-secrets/`는 GitOps 대상이 아니고, 오직 bootstrap.sh가 손으로 적용한다.
// 매니페스트를 새로 만들고 스크립트에 추가하지 않으면 파일은 저장소에 있는데 클러스터에는
// 영영 들어가지 않는다.
//
// 증상이 고약하다: 적용 안 된 ExternalSecret은 오류를 내지 않는다. 그냥 Secret이 안 생기고,
// 그걸 마운트하는 파드가 ContainerCreating에서 멈춘다. 원인에서 한 칸 떨어진 곳에서 터진다.
const ES_DIR = path.join(REPO_ROOT, K8S, "external-secrets");
if (fs.existsSync(ES_DIR)) {
  const bootstrapPath = path.join(ES_DIR, "bootstrap.sh");
  if (!fs.existsSync(bootstrapPath)) {
    r.fail(
      `ExternalSecret 적용 경로 없음: ${K8S}/external-secrets/bootstrap.sh 가 없다. ` +
        `이 디렉터리는 ArgoCD 대상이 아니라 스크립트로만 적용된다`
    );
  } else {
    const bootstrap = read(bootstrapPath);
    for (const name of fs.readdirSync(ES_DIR)) {
      if (!/\.(ya?ml)$/.test(name)) continue;
      const src = read(path.join(ES_DIR, name));
      if (!/^\s*kind:\s*ExternalSecret\s*$/m.test(src)) continue;
      if (!bootstrap.includes(name)) {
        r.fail(
          `ExternalSecret이 적용되지 않는다: ${K8S}/external-secrets/${name}: ` +
            `bootstrap.sh가 이 파일을 apply하지 않는다. ArgoCD는 overlays만 보므로 ` +
            `여기 없으면 클러스터에 영영 들어가지 않는다(Secret 없음 → 마운트하는 파드가 기동 실패)`
        );
      }
    }
  }
}

r.done();
