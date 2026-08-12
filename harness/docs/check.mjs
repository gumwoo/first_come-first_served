// 문서 하네스: **문서가 저장소의 현재 상태를 반대로 설명하는 것**을 잡는다.
//
// 왜 필요한가: 이 저장소에는 이미 문서 관련 규칙이 둘 있다(백엔드 규칙 ⑨ 스키마 문서 존재,
// 규칙 ⑮ 끊어진 문서 참조). 그런데 **둘 다 방향이 "코드 → 문서"다** — 코드가 가리키는 문서가
// 있느냐만 본다. 반대 방향, 즉 **문서가 코드의 상태를 단언하는 경우**는 아무도 보지 않았다.
//
// 2026-08-12 리뷰에서 그 대가가 한꺼번에 드러났다.
//   1) k8s/README.md 의 "아직 없는 것" 네 항목이 **전부 실존**했다(Kafka CR·ArgoCD Application·
//      kube-prometheus-stack·ExternalSecret). 절 제목 자체가 거짓이 됐다.
//   2) 같은 README 안에서 ESO를 "쓴다(2026-08-11~)"와 "아직 하지 않았다"가 공존했다.
//   3) IMP-004가 TS-024를 "(미수정)"으로 가리키는데 TS-024는 이미 `상태: 해결`이었다.
//      그 드리프트는 몇 년이 아니라 **같은 날 몇 시간 만에** 생겼다.
//
// 코드는 컴파일이라도 되지만 문서의 단언은 아무것도 검증하지 않는다. 정적으로만 잡을 수 있다.
//
// 여기서 다루지 않는 것: 자연어 자기모순("했다" vs "안 했다")은 정적 규칙으로 판정할 수 없다.
// 그건 규칙이 아니라 리뷰의 몫이다. 이 하네스는 **기계가 확인 가능한 단언**만 검사한다.

import fs from "node:fs";
import path from "node:path";
import { walk, read, Reporter, REPO_ROOT } from "../lib/util.mjs";

const r = new Reporter("docs");
const DOCS = process.env.HARNESS_DOCS_DIR || "docs";
const EXTRA = process.env.HARNESS_DOCS_EXTRA || "k8s"; // 루트 밖 README도 대상

// DOCS와 EXTRA가 겹칠 수 있다(fixture는 한 디렉터리를 양쪽에 준다) — 중복 스캔을 막는다.
const docFiles = [...new Set([...walk(DOCS, [".md"]), ...walk(EXTRA, [".md"])])];
if (docFiles.length === 0) {
  r.fail(`문서를 하나도 못 찾았다: ${DOCS}/ — 규칙이 무력화된 상태`);
  r.done();
}

// ---------- ⑯ "아직 없다"고 단언한 경로가 실제로 존재하면 실패 ----------
//
// 자연어로만 "없다"고 쓰면 기계가 못 읽는다. 그래서 **경로를 백틱으로 적는 것**을 규약으로 삼고,
// 그 절 안의 백틱 경로만 검사한다. 규약을 안 지킨 문서는 이 규칙이 보호하지 못하지만,
// 지킨 문서는 파일이 생기는 순간 CI가 알려 준다.
const ABSENT_HEADING = /^#{2,4}\s.*(아직 없는 것|아직 없다|없는 것|미구현 목록)/;
const NEXT_HEADING = /^#{1,4}\s/;
// 경로처럼 보이는 백틱 토큰: 슬래시가 있고 확장자로 끝난다.
const PATHISH = /`([A-Za-z0-9_./-]+\.(?:ya?ml|json|java|ts|tsx|mjs|sql|md|sh|tf))`/g;

for (const file of docFiles) {
  const rel = path.relative(REPO_ROOT, file);
  const lines = read(file).split("\n");
  let inAbsent = false;
  for (const line of lines) {
    if (ABSENT_HEADING.test(line)) {
      inAbsent = true;
      continue;
    }
    if (inAbsent && NEXT_HEADING.test(line)) inAbsent = false;
    if (!inAbsent) continue;

    for (const m of line.matchAll(PATHISH)) {
      const claimed = m[1];
      // 문서 기준 상대경로와 저장소 루트 기준을 모두 시도한다 — 어느 쪽으로 적어도 잡히게.
      const candidates = [
        path.join(REPO_ROOT, claimed),
        path.join(path.dirname(file), claimed),
      ];
      const found = candidates.find((p) => fs.existsSync(p));
      if (found) {
        r.fail(
          `없다고 단언한 대상이 실존한다: ${rel} — "${claimed}" ` +
            `(실제: ${path.relative(REPO_ROOT, found)}). 문서를 현재 상태로 고칠 것`
        );
      }
    }
  }
}

// ---------- ⑰ 문서가 붙인 상태 표기 ↔ 대상 문서의 상태 줄 ----------
//
// 한 문서가 다른 문서를 "(미수정)"으로 가리키는데 그 문서 머리말이 `상태: 해결`이면
// 둘 중 하나는 거짓이다. 양쪽 다 정형 필드라 기계로 대조할 수 있다.
// DOCS 기준으로 잡는다 — 고정 경로로 두면 fixture가 실제 docs/를 읽어 격리가 깨진다.
const DOC_DIRS = {
  TS: `${DOCS}/troubleshooting`,
  ADR: `${DOCS}/decisions`,
  IMP: `${DOCS}/improvements`,
};

// 대상 문서의 상태를 미리 읽어 둔다(번호 → 상태 줄).
const statusByRef = {};
for (const [prefix, dir] of Object.entries(DOC_DIRS)) {
  const abs = path.join(REPO_ROOT, dir);
  if (!fs.existsSync(abs)) continue;
  for (const name of fs.readdirSync(abs)) {
    if (!name.toUpperCase().startsWith(prefix + "-") || !name.endsWith(".md")) continue;
    const digits = name.slice(prefix.length + 1).split("-")[0];
    if (!/^[0-9]+$/.test(digits)) continue;
    const line = read(path.join(abs, name))
      .split("\n")
      .find((l) => /^-\s*(상태|Status)\s*:/.test(l));
    if (line) statusByRef[`${prefix}-${Number(digits)}`] = line;
  }
}
if (Object.keys(statusByRef).length === 0) {
  r.fail(`문서 상태 줄을 하나도 못 읽었다 — 규칙 ⑰이 무력화된 상태`);
}

// 참조 **바로 뒤에** 붙은 미해결 표기만 본다. 마크다운 링크는 사이에 `](경로)`가 끼므로 허용한다.
//
// ⚠️ 인접성을 요구하는 이유: 초안은 "같은 줄에 참조가 있고 어딘가에 (미수정)이 있으면"으로
// 판정했는데, 그러면 **상태를 설명하는 문장 자체를 잡는다.**
//   TS-902는 이미 해결됐으므로 위 "(미수정)"은 거짓이다.   ← 이런 줄이 오탐으로 걸렸다
// 드리프트는 "참조 옆에 붙인 딱지"에서 생기지, 본문 설명에서 생기지 않는다.
const REF_THEN_MARK =
  /\b(TS|ADR|IMP)-([0-9]{1,4})\b(?:\]\([^)]*\))?[ \t]*\((?:[^()]{0,20})(미수정|미해결|미실시|수정 보류|보류)(?:[^()]{0,20})\)/g;

// "아직 안 끝났다"를 뜻하는 표현. 상태 줄에 이게 있으면 참조 쪽의 미해결 표기와 **양립한다.**
const OPEN_TOKENS = /미해결|미완료|미실시|미적용|미수정|미검증|보류|하지 않(음|았)|안 함|Proposed|Open/i;
const DONE_TOKENS = /해결|완료|Resolved|Done|Accepted/i;

/**
 * 이 대조는 **명백한 모순일 때만** 실패한다.
 *
 * 상태 줄은 한 문장에 끝난 것과 안 끝난 것을 함께 담는 경우가 많다.
 *   TS-018  "원인 규명 완료 — **수정은 하지 않음**"   ← 규명은 끝, 수정은 안 함
 *   ADR-011 "구현 완료 · 통합테스트 통과 / 실 PG 실증은 미실시"
 * 이런 줄은 참조 쪽의 "(보류)" "(미실시)"와 **서로 맞는 말**이다. 초안은 '완료'만 보고
 * 둘 다 오탐으로 잡았다 — 오탐을 내는 규칙은 곧 꺼지므로 없느니만 못하다.
 *
 * 그래서 상태 줄에 미해결 표현이 하나라도 있으면 양립으로 보고 넘어간다.
 * 놓치는 경우(false negative)는 생기지만, 이 규칙이 노리는 것은
 * "대상은 완전히 끝났는데 참조는 아직 안 끝났다고 말하는" 명백한 드리프트다.
 */
function contradicts(statusLine) {
  if (OPEN_TOKENS.test(statusLine)) return false; // 부분 완료 — 미해결 표기와 양립
  return DONE_TOKENS.test(statusLine);
}

const seen = new Set(); // 같은 위치가 두 번 보고되지 않게
for (const file of docFiles) {
  const rel = path.relative(REPO_ROOT, file);
  let inFence = false;
  read(file)
    .split("\n")
    .forEach((raw, i) => {
      // 코드블록과 인라인 코드는 **인용**이다 — 과거 상태를 증거로 옮겨 적는 자리라
      // 현재 상태에 대한 단언으로 볼 수 없다. TS-025가 드리프트를 표에 인용했다가
      // 이 규칙에 걸렸고, 그때 이 예외를 넣었다.
      if (/^\s*```/.test(raw)) {
        inFence = !inFence;
        return;
      }
      if (inFence) return;
      const line = raw.replace(/`[^`]*`/g, ""); // 인라인 코드 제거
      for (const m of line.matchAll(REF_THEN_MARK)) {
        const ref = `${m[1].toUpperCase()}-${m[2]}`;
        const status = statusByRef[`${m[1].toUpperCase()}-${Number(m[2])}`];
        if (!status) continue; // 끊어진 참조는 백엔드 규칙 ⑮의 몫
        if (!contradicts(status)) continue;
        const key = `${rel}|${i}|${ref}`;
        if (seen.has(key)) continue;
        seen.add(key);
        r.fail(
          `문서 상태가 어긋난다: ${rel}:${i + 1} — ${ref}를 "${m[3]}"로 가리키는데 ` +
            `대상 문서는 "${status.trim()}"`
        );
      }
    });
}

r.done();
