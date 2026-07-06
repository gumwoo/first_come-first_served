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

function normalize(p) {
  return p.replace(/\{[^}]+\}/g, "{id}").replace(/\/+$/, "") || "/";
}

r.done();
