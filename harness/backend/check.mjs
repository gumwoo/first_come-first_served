// 백엔드 하네스: 스택 / enum / API endpoint / error-code / 계층 import 를
// contracts/ 와 diff. 위반 시 exit 1.
//
// 검사 방향(중요):
//  - 코드에 있으나 계약에 없는 것 = drift → 실패
//  - 계약에 있으나 코드에 아직 없는 것 = 미구현(허용) — 슬라이스 done 시 done-criteria가 잡음
//  - 단, 코드+계약 양쪽에 존재하는 enum은 값이 정확히 일치해야 함

import { loadYaml, walk, read, globToRe, Reporter, REPO_ROOT } from "../lib/util.mjs";
import fs from "node:fs";
import path from "node:path";

const r = new Reporter("backend");
const API = process.env.HARNESS_API_DIR || "apps/api";

// ---------- 1. 허용 스택 ----------
const stack = loadYaml("contracts/allowed-stack.yaml").backend;
const allowedGroups = new Set(stack.allowed_groups);
const allowedArtifacts = new Set([
  ...stack.allowed_artifacts,
  ...(stack.test_allowed_artifacts ?? []),
]);
const gradlePath = path.join(REPO_ROOT, API, "build.gradle.kts");
if (fs.existsSync(gradlePath)) {
  const gradle = read(gradlePath);
  const depRe = /(?:implementation|api|runtimeOnly|compileOnly|annotationProcessor|testImplementation|testRuntimeOnly)\(\s*"([^"]+)"/g;
  for (const m of gradle.matchAll(depRe)) {
    const dep = m[1];
    // group:artifact[:version[:classifier]]
    const parts = dep.split(":");
    if (parts.length < 2) continue; // 플러그인/기타
    const group = parts[0];
    const ga = `${parts[0]}:${parts[1]}`;
    if (allowedGroups.has(group)) continue;
    if (allowedArtifacts.has(ga)) continue;
    r.fail(`허용되지 않은 백엔드 의존성: ${ga} (allowed-stack.yaml에 추가 필요)`);
  }
}

// ---------- 2. 자바 소스 수집 ----------
const javaFiles = walk(API + "/src/main/java", [".java"]);

// ---------- 3. enum 일치 ----------
const enums = loadYaml("contracts/enums.yaml");
for (const file of javaFiles) {
  const src = read(file);
  const em = src.match(/public\s+enum\s+(\w+)\s*\{([\s\S]*?)\}/);
  if (!em) continue;
  const name = em[1];
  if (!enums[name]) continue; // 계약에 없는 enum은 무시(도메인 비계약 enum 허용)
  const body = em[2];
  // enum 상수 추출(괄호/세미콜론 앞 식별자)
  const consts = [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|;|$)/gm)].map((x) => x[1]);
  const expected = enums[name].values;
  const got = consts.filter((c) => expected.includes(c) || true); // 순서 무시 비교
  const missing = expected.filter((v) => !consts.includes(v));
  const extra = consts.filter((v) => !expected.includes(v));
  if (missing.length) r.fail(`enum ${name}: 계약에 있으나 코드에 없음 → ${missing.join(", ")}`);
  if (extra.length) r.fail(`enum ${name}: 코드에 있으나 계약에 없음(문서화 안 된 상태값) → ${extra.join(", ")}`);
}

// ---------- 4. error-code 일치 ----------
const errorCodes = new Set(loadYaml("contracts/error-codes.yaml").codes.map((c) => c.code));
for (const file of javaFiles) {
  if (!file.endsWith("ErrorCode.java")) continue;
  const src = read(file);
  const body = src.match(/enum\s+ErrorCode\s*\{([\s\S]*?)\}/)?.[1] ?? "";
  const consts = [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*\(/gm)].map((x) => x[1]);
  for (const c of consts) {
    if (!errorCodes.has(c)) r.fail(`ErrorCode ${c}: contracts/error-codes.yaml에 없음`);
  }
}

// ---------- 5. API endpoint drift ----------
const apiContract = loadYaml("contracts/api.yaml");
const contractSet = new Set(
  apiContract.endpoints.map((e) => `${e.method} ${normalize(e.path)}`)
);
// 매핑 애너테이션 인자에서 경로 리터럴을 모두 추출.
// 지원: "x" / value="x" / path="x" / {"a","b"} 배열 / 인자 없음("")
// 미지원(보수적으로 경고): 상수 참조 등 문자열 리터럴이 없는 경우.
function extractPaths(args) {
  // value=/path= 접두 제거 후 첫 인자 그룹만 본다(method= 등은 무시)
  const trimmed = args.trim();
  if (trimmed === "") return [""]; // @GetMapping → 클래스 경로만
  const lits = [...trimmed.matchAll(/"([^"]*)"/g)].map((x) => x[1]);
  if (lits.length === 0) return [null]; // 리터럴 없음(상수 참조 등) → 검사 불가 표시
  // value=/path= 키가 명시된 경우 그 값만, 아니면 모든 리터럴(배열 포함)
  const keyed = [...trimmed.matchAll(/(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/g)];
  if (keyed.length) {
    return keyed.flatMap((k) => [...k[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]));
  }
  return lits;
}

// 클래스 레벨 @RequestMapping (value=/path=/직접 리터럴 모두)
const classMapRe = /@RequestMapping\s*\(([\s\S]*?)\)/;
const mapRe = /@(Get|Post|Put|Patch|Delete)Mapping\s*\(([\s\S]*?)\)/g;
// 인자 없는 @GetMapping (괄호 없음) 도 포착
const mapBareRe = /@(Get|Post|Put|Patch|Delete)Mapping(?!\s*\()/g;

for (const file of javaFiles) {
  const src = read(file);
  if (!/@RestController/.test(src)) continue;
  const classArgs = src.match(classMapRe)?.[1] ?? "";
  const bases = classArgs ? extractPaths(classArgs) : [""];

  const join = (base, sub) => {
    if (sub === null) return null;
    const b = base ?? "";
    const s = sub.startsWith("/") || sub === "" ? sub : "/" + sub;
    return normalize(b + s);
  };

  const checkOne = (method, subPaths) => {
    for (const base of bases) {
      for (const sub of subPaths) {
        const full = join(base, sub);
        if (full === null) {
          r.fail(`endpoint 경로를 정적 추출 불가(상수 참조 등): ${method} in ${path.relative(REPO_ROOT, file)} — 리터럴 경로 사용 권장`);
          continue;
        }
        const key = `${method} ${full}`;
        if (!contractSet.has(key)) {
          r.fail(`계약에 없는 endpoint: ${key} (contracts/api.yaml에 등록 필요)`);
        }
      }
    }
  };

  for (const m of src.matchAll(mapRe)) {
    checkOne(m[1].toUpperCase(), extractPaths(m[2]));
  }
  for (const m of src.matchAll(mapBareRe)) {
    checkOne(m[1].toUpperCase(), [""]);
  }
}

// ---------- 6. 계층 import 위반 ----------
const layer = loadYaml("contracts/layer-rules.yaml").backend;
for (const rule of layer.forbidden_imports) {
  const fromRe = globToRe(rule.from);
  const impRe = globToRe(rule.not_import);
  for (const file of javaFiles) {
    const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
    if (!fromRe.test(rel)) continue;
    const src = read(file);
    for (const im of src.matchAll(/^import\s+([\w.]+);/gm)) {
      const imp = im[1].replace(/\./g, "/");
      if (impRe.test(imp)) {
        r.fail(`계층 위반: ${rel} → ${im[1]} (${rule.reason})`);
      }
    }
  }
}

// ---------- 7. 금지 패턴 (coding-standards / secure-coding [H]) ----------
const FORBIDDEN = [
  { re: /\.printStackTrace\s*\(/, msg: "printStackTrace() 금지 → slf4j 로거 사용" },
  { re: /System\.(out|err)\s*\.\s*print/, msg: "System.out/err 출력 금지 → 로거 사용" },
  {
    re: /\b(password|passwd|secret|apiKey|api_key|privateKey)\s*=\s*"[^"]+"/i,
    msg: "시크릿 하드코딩 의심 → 환경변수 사용",
  },
  {
    re: /(createQuery|createNativeQuery)\s*\(\s*"[^"]*"\s*\+/,
    msg: "문자열 연결 SQL/JPQL 금지(인젝션) → 바인드 파라미터",
  },
];
for (const file of javaFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const src = read(file);
  for (const f of FORBIDDEN) {
    if (f.re.test(src)) r.fail(`금지 패턴: ${f.msg} (${rel})`);
  }
  // 컨트롤러 내 try/catch (예외 삼키기) 금지
  if (/\/controller\//.test(rel) && /\btry\s*\{[\s\S]*\bcatch\s*\(/.test(src)) {
    r.fail(`컨트롤러 내 try/catch 금지(예외는 전역 핸들러로): ${rel}`);
  }
}

// ---------- 7b. status 변경 UPDATE는 WHERE에 status 가드 필수 (TS-011 재발 방지) ----------
// @Query UPDATE가 status를 set하면서 WHERE에 status 조건(= / in)이 없으면 check-then-act 레이스 위험
// (만료 sweep이 이미 SOLD/CONVERTED된 행을 무조건 덮어쓰는 등). 조건부 전이는 프로젝트 원칙(ADR-003/006).
// 의도적 무가드가 정말 필요하면 해당 @Query 바로 앞에 "harness:allow-unguarded-status" 주석으로 예외 처리.
const queryRe = /@Query\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)/g;
for (const file of javaFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const src = read(file);
  for (const m of src.matchAll(queryRe)) {
    const literals = m[1].match(/"(?:[^"\\]|\\.)*"/g) || [];
    const q = literals.map((s) => s.slice(1, -1)).join(" ").toLowerCase();
    if (!/^\s*update\b/.test(q)) continue;
    const [setPart, ...whereRest] = q.split(/\bwhere\b/);
    if (!/status\s*=/.test(setPart)) continue; // status를 set하지 않는 UPDATE는 무관
    const guarded = /status\s*(=|in\b)/.test(whereRest.join(" where "));
    const allowed = src.slice(Math.max(0, m.index - 200), m.index).includes("harness:allow-unguarded-status");
    if (!guarded && !allowed) {
      r.fail(`status 변경 UPDATE에 WHERE status 가드 없음(check-then-act 레이스 위험, TS-011): ${rel}`);
    }
  }
}

// ---------- 8. application.yml/properties 시크릿 하드코딩 ----------
// 민감 키에 ${...} 플레이스홀더가 아닌 리터럴 값이 오면 실패.
// 정상: client-secret: ${NAVER_CLIENT_SECRET:}  / 위반: client-secret: AbCd123
const SECRET_KEYS = /(client-secret|secret|password|passwd|private-key|access-key|secret-key|api-key|service-key|token)/i;
const ymlFiles = [
  ...walk(API + "/src/main/resources", [".yml", ".yaml", ".properties"]),
];
for (const file of ymlFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const isProps = file.endsWith(".properties");
  const lines = read(file).split(/\r?\n/);
  for (const [i, raw] of lines.entries()) {
    const line = raw.replace(/#.*$/, ""); // 주석 제거
    const m = isProps
      ? line.match(/^\s*([\w.\-]*?(client-secret|secret|password|passwd|private-key|access-key|secret-key|api-key|service-key|token)[\w.\-]*)\s*=\s*(.+)$/i)
      : line.match(/^\s*([\w.\-]+)\s*:\s*(.+)$/);
    if (!m) continue;
    const key = isProps ? m[1] : m[1];
    const val = (isProps ? m[3] : m[2]).trim().replace(/^["']|["']$/g, "");
    if (!SECRET_KEYS.test(key)) continue;
    if (val === "" || val.includes("${")) continue; // 빈 값/플레이스홀더는 정상
    if (/^\d+(\.\d+)?$/.test(val) || /^(true|false)$/i.test(val)) continue; // 숫자/불린(ttl 등)은 시크릿 아님
    if (/^https?:\/\//.test(val)) continue; // URL(token-uri 등 공개 엔드포인트)은 시크릿 아님
    r.fail(`시크릿 하드코딩(설정파일): ${rel}:${i + 1} '${key}' → 환경변수(\${...}) 사용`);
  }
}

// ---------- 9. Flyway CREATE TABLE ↔ docs/db/<table>.md 존재 ----------
// 마이그레이션에서 테이블을 만들면 대응 스키마 문서가 반드시 있어야 함(drift 방지).
const migrationFiles = walk(API + "/src/main/resources/db/migration", [".sql"]);
const createTableRe = /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["`']?(\w+)["`']?/gi;
const seenTables = new Set();
for (const file of migrationFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const src = read(file);
  for (const m of src.matchAll(createTableRe)) {
    const table = m[1].toLowerCase();
    if (seenTables.has(table)) continue;
    seenTables.add(table);
    const docPath = path.join(REPO_ROOT, "docs", "db", `${table}.md`);
    if (!fs.existsSync(docPath)) {
      r.fail(`스키마 문서 누락: CREATE TABLE ${table} (${rel}) → docs/db/${table}.md 필요`);
    }
  }
}

// ---------- 10. 보안 정적 룰 (리뷰 발견 → feedback-routing 승격) ----------
for (const file of javaFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const src = read(file);

  // (a) JWT 토큰에는 반드시 type claim(access/refresh)이 있어야 함.
  //     type 미구분 시 refresh를 access로 오용하는 등 보안 결함 발생.
  for (const m of src.matchAll(/Jwts\.builder\(\)([\s\S]*?)\.compact\(\)/g)) {
    if (!/\.claim\(\s*"type"/.test(m[1])) {
      r.fail(`JWT 토큰에 type claim 누락(access/refresh 구분 필요): ${rel}`);
    }
  }

  // (b) actuator 전체(/actuator/**) permitAll 금지 — metrics/prometheus 정보 노출.
  if (/["']\/actuator\/\*\*["']/.test(src) && /permitAll/.test(src)) {
    r.fail(`actuator 전체 permitAll 금지(정보 노출): ${rel} — health/info만 공개`);
  }
}

// ---------- 11. JPA / Spring 정적 지뢰 가드 ----------
for (const file of javaFiles) {
  const rel = path.relative(REPO_ROOT, file).replace(/\\/g, "/");
  const src = read(file);

  // (a) @Enumerated는 STRING이어야 함(기본 ORDINAL은 enum 순서 변경 시 DB 값 어긋남).
  for (const m of src.matchAll(/@Enumerated\s*(\([^)]*\))?/g)) {
    if (!/EnumType\.STRING/.test(m[1] || "")) {
      r.fail(`@Enumerated는 EnumType.STRING 필수(ORDINAL 지뢰): ${rel}`);
    }
  }

  // (b) @Entity에 Lombok @Data/@EqualsAndHashCode 금지(프록시·연관관계 equals/hashCode 지뢰).
  if (/@Entity\b/.test(src) && /(@Data|@EqualsAndHashCode)\b/.test(src)) {
    r.fail(`@Entity에 @Data/@EqualsAndHashCode 금지 → @Getter 등 사용: ${rel}`);
  }

  // (c) 필드/세터 주입 금지 — 생성자 주입만(layer-rules). @Autowired 사용 자체를 차단.
  if (/@Autowired\b/.test(src)) {
    r.fail(`@Autowired 금지 → 생성자 주입 사용: ${rel}`);
  }

  // (d) private 메서드 @Transactional 금지(프록시 미적용으로 트랜잭션이 조용히 안 걸림).
  //     주석에 방해받지 않도록 주석 제거 후 검사.
  const noComments = src.replace(/\/\/[^\n]*/g, "").replace(/\/\*[\s\S]*?\*\//g, "");
  if (/@Transactional\b(?:\([^)]*\))?\s*(?:@\w+(?:\([^)]*\))?\s*)*private\b/.test(noComments)) {
    r.fail(`private 메서드에 @Transactional 금지(프록시 미적용): ${rel}`);
  }

  // (e) 전체 개방 금지 — anyRequest().permitAll() 또는 "/**" permitAll.
  if (/anyRequest\(\)\s*\.\s*permitAll/.test(src)) {
    r.fail(`anyRequest().permitAll() 금지(전체 API 개방): ${rel}`);
  }
  if (/["']\/\*\*["'][^;]*permitAll/.test(src)) {
    r.fail(`"/**" permitAll 금지(전체 경로 개방): ${rel}`);
  }
}

// ---------- 12. Flyway 버전 유일성 ----------
// 같은 버전 번호(V6__* 두 개 등)는 Flyway가 실패시키는 지뢰 → 정적으로 미리 차단.
const versionSeen = new Map();
for (const file of migrationFiles) {
  const base = path.basename(file);
  const vm = base.match(/^V(\d+(?:[._]\d+)*)__/);
  if (!vm) continue;
  const v = vm[1];
  if (versionSeen.has(v)) {
    r.fail(`Flyway 버전 중복: V${v} (${base} ↔ ${versionSeen.get(v)}) — 새 버전 번호 사용`);
  } else {
    versionSeen.set(v, base);
  }
}

// ---------- 13. 구현된 이벤트는 실제로 발행돼야 함 ----------
// events.yaml의 implemented(= 완료 슬라이스가 발행해야 하는 이벤트)가 백엔드 소스에
// 문자열로 존재하는지 확인. 계약엔 선언했는데 발행부가 없는 "미구현 stale"을 잡는다.
// (미구현 이벤트는 implemented에 넣지 않으므로 걸리지 않음 = 미구현 허용 철학 유지.)
const eventsContract = loadYaml("contracts/events.yaml");
const publishSet = new Set(eventsContract.publishes ?? []);
const allJavaSrc = javaFiles.map((f) => read(f)).join("\n");
for (const ev of eventsContract.implemented ?? []) {
  if (!publishSet.has(ev)) {
    r.fail(`이벤트 계약 위반: implemented '${ev}'가 publishes에 없음(events.yaml)`);
  }
  if (!allJavaSrc.includes(`"${ev}"`)) {
    r.fail(`이벤트 미발행: implemented '${ev}'를 백엔드가 발행하지 않음(소스에 "${ev}" 없음)`);
  }
}

// ---------- 14. 파괴적 DDL 금지 (무중단 배포 전제) ----------
// 롤링 배포 중에는 구버전 Pod와 신버전 Pod가 같은 DB를 동시에 본다. 신버전이 기동하며
// Flyway가 컬럼을 지우면 아직 살아 있는 구버전 Pod가 즉시 터진다.
// 이건 컴파일·테스트·CI가 전혀 못 잡는 무증상 결함이라(단일 프로세스에서는 정상 동작)
// 정적으로 막는다. 상세: docs/deployment/zero-downtime-deployment.md §8
//
// 금지가 아니라 "명시적 승인"이다. SET NOT NULL도 데이터 백필 후 별도 릴리스에서는 안전하다.
// 불가피하면 해당 마이그레이션 파일에 사유와 함께 예외 주석을 남긴다:
//   -- harness:allow-destructive-ddl: V13에서 Expand 완료, 구버전 참조 없음
const DESTRUCTIVE_DDL = [
  [/\bdrop\s+table\b/i, "DROP TABLE"],
  [/\bdrop\s+column\b/i, "DROP COLUMN"],
  // "ALTER COLUMN x TYPE ..." / "... SET DATA TYPE ..." (DROP NOT NULL 등은 걸리지 않음)
  [/\balter\s+column\s+\S+\s+(?:set\s+data\s+)?type\b/i, "ALTER COLUMN ... TYPE"],
  [/\brename\s+(?:column|table|to)\b/i, "RENAME"],
  [/\bset\s+not\s+null\b/i, "SET NOT NULL"],
];
const ALLOW_DDL_RE = /--\s*harness:allow-destructive-ddl\s*:?\s*(\S.*)?/i;

for (const file of migrationFiles) {
  const raw = read(file);
  const base = path.basename(file);
  const allow = raw.match(ALLOW_DDL_RE);

  // 주석 안의 문구가 오탐을 내지 않도록 SQL 주석을 제거한 뒤 검사한다.
  const sql = raw.replace(/--[^\n]*/g, " ").replace(/\/\*[\s\S]*?\*\//g, " ");
  const hits = DESTRUCTIVE_DDL.filter(([re]) => re.test(sql)).map(([, label]) => label);

  if (!hits.length) {
    // 쓰지도 않으면서 예외만 달아 둔 주석은 다음 사람을 오해시킨다.
    if (allow) r.fail(`불필요한 예외 주석: ${base} — 파괴적 DDL이 없는데 allow-destructive-ddl이 달려 있음`);
    continue;
  }
  if (!allow) {
    r.fail(
      `파괴적 DDL: ${base} — ${hits.join(", ")}. 롤링 배포 중 구버전 Pod가 깨진다. ` +
        `Expand-Contract로 나누거나, 불가피하면 "-- harness:allow-destructive-ddl: <사유>" 주석으로 승인`
    );
  } else if (!allow[1]) {
    // 예외를 열어 주되 근거는 반드시 남게 한다.
    r.fail(`예외 사유 누락: ${base} — "-- harness:allow-destructive-ddl: <사유>" 형식으로 근거를 적을 것`);
  }
}

// ---------- 15. 존재하지 않는 문서 번호 참조(끊어진 근거) ----------
// 코드 주석에 "TS-014", "ADR-012", "IMP-013" 같은 번호를 적어 두는 것은 이 저장소의 습관이다.
// 근거를 코드 옆에 두면 나중에 "왜 이렇게 했나"를 되짚을 수 있기 때문이다.
//
// 문제는 **번호를 먼저 적고 문서를 나중에 쓰는 순서**다. 실제로 V15 마이그레이션이 두 곳에서
// TS-014를 참조했는데 그 문서가 없었다 — 읽는 사람은 근거를 찾아가려다 빈손으로 돌아온다.
// 근거를 가리키는 척하는 주석은 근거가 없는 것보다 나쁘다(찾는 시간까지 쓰게 만든다).
//
// 컴파일·테스트는 주석을 보지 않으므로 정적으로만 잡을 수 있다.
const DOC_DIRS = {
  TS: "docs/troubleshooting",
  ADR: "docs/decisions",
  IMP: "docs/improvements",
};

// 각 디렉터리에 실제로 존재하는 번호를 모은다(파일명 앞머리 기준: TS-014-....md).
// 정규식을 쓰지 않는다 — 템플릿 리터럴 안의 \d 는 이스케이프가 아니라 그냥 d 로 죽는다.
// 초안이 그렇게 작성돼 목록이 조용히 비었고, 규칙이 아무것도 잡지 못했다.
const existingDocs = {};
for (const [prefix, dir] of Object.entries(DOC_DIRS)) {
  const abs = path.join(REPO_ROOT, dir);
  const nums = new Set();
  if (fs.existsSync(abs)) {
    for (const name of fs.readdirSync(abs)) {
      if (!name.toUpperCase().startsWith(prefix + "-")) continue;
      const digits = name.slice(prefix.length + 1).split("-")[0];
      if (/^[0-9]+$/.test(digits)) nums.add(String(Number(digits)));
    }
  }
  // 목록이 비면 이 규칙은 모든 참조를 끊어진 것으로 보거나(오탐 폭발) 아무것도 못 잡는다.
  // 조용히 무력화되는 쪽이 더 위험하므로 그 상태 자체를 실패로 만든다.
  if (nums.size === 0) {
    r.fail(`문서 번호를 하나도 못 읽었다: ${dir} — 규칙 15가 무력화된 상태`);
  }
  existingDocs[prefix] = nums;
}

const DOC_REF_RE = /\b(TS|ADR|IMP)-([0-9]{1,4})\b/g;
const danglingSeen = new Set();

for (const file of [...javaFiles, ...migrationFiles]) {
  const raw = read(file);
  const rel = path.relative(REPO_ROOT, file);
  for (const m of raw.matchAll(DOC_REF_RE)) {
    const prefix = m[1].toUpperCase();
    const num = String(Number(m[2]));
    if (existingDocs[prefix].has(num)) continue;
    const key = `${rel}|${prefix}-${m[2]}`;
    if (danglingSeen.has(key)) continue;
    danglingSeen.add(key);
    r.fail(
      `끊어진 문서 참조: ${rel} → ${prefix}-${m[2]} (${DOC_DIRS[prefix]}/에 해당 문서 없음). ` +
        `문서를 먼저 쓰거나, 아직 없다면 번호를 적지 말 것`
    );
  }
}

// ---------- 16. 응답의 LocalDateTime은 오프셋을 달고 나가야 함 ----------
// LocalDateTime은 JSON에 "2026-08-09T06:56:54"처럼 타임존 없이 실린다. JS의 new Date()는
// 오프셋이 없으면 그 값을 **브라우저 로컬 시간**으로 해석하므로(ES 명세), 서버 컨테이너가
// UTC이고 사용자가 KST면 9시간이 어긋난다.
//
// 실제로 좌석 선점이 이것 때문에 깨졌다 — 5분 만료를 브라우저가 9시간 전으로 계산해서
// '선택 완료' 즉시 만료 화면으로 튕겼다. **로컬에서는 재현되지 않는다**(JVM도 브라우저도 KST).
// 그래서 테스트가 아니라 정적 규칙으로 막는다.
const dtoWithLocalDateTime = [];
for (const file of walk(API + "/src/main/java", [".java"])) {
  const rel = path.relative(REPO_ROOT, file);
  if (!/[\\/]dto[\\/]/.test(rel)) continue;
  if (/\bLocalDateTime\s+\w+/.test(read(file))) dtoWithLocalDateTime.push(rel);
}
if (dtoWithLocalDateTime.length > 0) {
  // 통과 조건: LocalDateTime 전용 직렬화기를 등록하면서 존 오프셋을 붙이는 설정이 있을 것.
  const hasOffsetSerializer = walk(API + "/src/main/java", [".java"]).some((f) => {
    const src = read(f);
    return (
      /serializerByType\(\s*LocalDateTime\.class/.test(src) &&
      /atZone\(|ISO_OFFSET_DATE_TIME|toOffsetDateTime\(/.test(src)
    );
  });
  if (!hasOffsetSerializer) {
    r.fail(
      `응답 DTO가 LocalDateTime을 노출하는데 오프셋 직렬화기가 없다 ` +
        `(${dtoWithLocalDateTime.slice(0, 3).join(", ")}${dtoWithLocalDateTime.length > 3 ? " 외" : ""}). ` +
        `타임존 없이 나가면 브라우저가 자기 로컬로 해석해 서버-클라이언트 시차만큼 어긋난다 ` +
        `— 에러가 아니라 조용히 다른 화면으로 빠진다(좌석 선점 즉시 만료)`
    );
  }
}

// ---------- 17. DB 커넥션 상한: (maxReplicas + maxSurge) × pool 이 DB 한도를 넘지 않는가 ----------
// 이 한도는 **곱셈으로 정해지는데 곱하는 자리가 코드에 없었다.** HPA maxReplicas(9)와 Hikari
// 기본 풀(10)이 각자 합리적이었지만 곱이 90이라 실질 한도 76을 넘었고, 부하로 9까지 확장되자
// 8번째 파드부터 Flyway가 커넥션을 얻지 못해 기동 실패했다(TS-021).
//
// ⚠️ **maxSurge를 빼면 안 된다.** 롤링 배포 중에는 새 Pod가 Ready가 된 뒤에 옛 Pod가 빠지므로
// (maxUnavailable=0, maxSurge=1) 순간적으로 maxReplicas + maxSurge 개가 공존한다. 평상시 값만
// 검사하면 "배포하는 순간에만 넘는" 구성을 통과시킨다 — 초안이 그 구멍을 갖고 있었고 리뷰에서
// 지적받아 고쳤다.
//
// 네 값이 서로 다른 곳에 흩어져 있어 사람이 맞추기 어렵다 — maxReplicas는 k8s/api-hpa.yaml,
// maxSurge는 k8s/api-deployment.yaml, 풀 크기는 application.yml, max_connections는
// **저장소 밖**(RDS 인스턴스 클래스에서 파생)이다.
// 마지막 값은 클래스별 실측치를 여기에 표로 둔다(추정 아님 — pg_settings로 직접 조회한 값).
const DB_MAX_CONNECTIONS = {
  "db.t4g.micro": 79, // 2026-08-10 pg_settings 실측
};
// 앱이 전부 쓰는 게 아니다. rdsadmin·운영 도구·마이그레이션 세션이 같은 풀에서 가져간다.
// 조사 시점 앱 외 세션이 7개였다(rdsadmin 3 + 기타 4). 여유를 포함해 20으로 잡는다.
const NON_APP_HEADROOM = 20;

{
  // ⚠️ 경로는 반드시 REPO_ROOT 기준으로 만든다. CI는 `harness/`에서 실행하므로
  // 저장소 상대 경로를 그대로 fs에 넘기면 cwd 기준으로 풀려 파일을 못 찾고,
  // 그러면 규칙이 **실패가 아니라 조용히 건너뛰어진다**(거짓 안전). 실제로 그렇게 통과했다.
  // fixture가 k8s 쪽 값을 갈아끼울 수 있게 열어둔다(maxSurge 항이 실제로 계산에 들어가는지 검증).
  const K8S = process.env.HARNESS_K8S_DIR || "k8s/base";
  const hpaFile = path.join(REPO_ROOT, K8S, "api-hpa.yaml");
  const depFile = path.join(REPO_ROOT, K8S, "api-deployment.yaml");
  const ymlFile = path.join(REPO_ROOT, API, "src/main/resources/application.yml");
  const rdsVars = path.join(REPO_ROOT, "infra/terraform/platform/modules/rds/variables.tf");

  // application.yml이 없는 fixture도 있으므로 그것만 선택적으로 다룬다(없으면 기본값 10).
  if (!fs.existsSync(hpaFile) || !fs.existsSync(rdsVars) || !fs.existsSync(depFile)) {
    r.fail("커넥션 상한 검사 대상 파일을 못 찾았다(api-hpa.yaml / api-deployment.yaml / rds variables.tf) — 규칙이 무력화된 상태");
  } else {
    const maxReplicas = Number(read(hpaFile).match(/^\s*maxReplicas:\s*(\d+)/m)?.[1]);
    // 설정이 없으면 HikariCP 기본값 10이다 — "안 정한 것"도 정해진 값으로 취급해야 한다.
    const poolRaw = fs.existsSync(ymlFile)
      ? read(ymlFile).match(/^\s*maximum-pool-size:\s*(?:\$\{[A-Z_]+:)?(\d+)/m)?.[1]
      : undefined;
    const pool = poolRaw ? Number(poolRaw) : 10;
    const cls = read(rdsVars).match(/variable\s+"instance_class"[\s\S]*?default\s*=\s*"([^"]+)"/)?.[1];
    // maxSurge는 정수 또는 백분율("25%")이다. 백분율이면 maxReplicas 기준으로 올림한다.
    const surgeRaw = read(depFile).match(/^\s*maxSurge:\s*"?([0-9]+%?)"?/m)?.[1];
    const maxSurge = surgeRaw === undefined
      // 명시하지 않으면 k8s 기본값이 25%다. 1로 가정하면 과소평가되므로 기본값대로 계산한다.
      ? Math.ceil((maxReplicas * 25) / 100)
      : surgeRaw.endsWith("%")
        ? Math.ceil((maxReplicas * parseInt(surgeRaw, 10)) / 100)
        : Number(surgeRaw);
    const maxConn = DB_MAX_CONNECTIONS[cls];

    if (!maxReplicas || !cls) {
      r.fail("커넥션 상한 검사에 필요한 값을 못 읽었다(maxReplicas/instance_class) — 규칙이 무력화된 상태");
    } else if (maxConn === undefined) {
      r.fail(
        `RDS 인스턴스 클래스 ${cls}의 max_connections를 모른다. ` +
          `harness/backend/check.mjs의 DB_MAX_CONNECTIONS에 **실측값**을 추가할 것 ` +
          `(pg_settings 조회. 공식 없이 추정하지 말 것)`
      );
    } else {
      const reserved = 3; // superuser_reserved_connections
      const budget = maxConn - reserved - NON_APP_HEADROOM;
      const peakPods = maxReplicas + maxSurge; // 롤링 중 공존 최대치
      const demand = peakPods * pool;
      if (demand > budget) {
        r.fail(
          `DB 커넥션 상한 초과: (maxReplicas ${maxReplicas} + maxSurge ${maxSurge}) × pool ${pool} = ${demand} > ` +
            `가용 ${budget} (${cls} max_connections ${maxConn} − reserved ${reserved} − 앱외여유 ${NON_APP_HEADROOM}). ` +
            `maxUnavailable=0이라 롤링 중 ${peakPods}개가 공존한다 — 그 순간 뒤쪽 파드가 커넥션을 ` +
            `얻지 못해 기동에 실패한다(TS-021)`
        );
      }
    }
  }
}

function normalize(p) {
  return p.replace(/\{[^}]+\}/g, "{id}").replace(/\/+$/, "") || "/";
}

r.done();
