// AI가 낸 근거 수치를 요약 JSON과 대조한다. 경로가 없거나 값이 다르면 실패다.
// 실패한 답은 원인 판정에 쓰지 않는다(triage.mjs가 undetermined로 바꾼다).

// "api.redis.completion_ms_mean", "series[3].tomcat_busy", "db.active_wait_events.IO:WALSync"
export function resolve(obj, field) {
  const parts = [];
  for (const seg of String(field).split(".")) {
    const m = seg.match(/^([^[\]]*)((?:\[\d+\])*)$/);
    if (!m) return { found: false };
    if (m[1]) parts.push(m[1]);
    for (const idx of m[2].matchAll(/\[(\d+)\]/g)) parts.push(Number(idx[1]));
  }
  let cur = obj;
  for (const p of parts) {
    if (cur == null || typeof cur !== "object" || !(p in cur)) return { found: false };
    cur = cur[p];
  }
  if (typeof cur === "boolean") return { found: true, value: cur ? 1 : 0 }; // 참·거짓 필드는 1·0으로 대조한다
  return { found: typeof cur === "number", value: cur };
}

// 같은 값을 반올림해 옮긴 정도는 허용한다(상대 1% 또는 절대 0.01).
export function sameNumber(a, b) {
  return Math.abs(a - b) <= Math.max(0.01, Math.abs(b) * 0.01);
}

export function verify(result, summary) {
  const failures = [];
  const items = [...(result.evidence ?? []).map((e) => ["evidence", e]), ...(result.counter_evidence ?? []).map((e) => ["counter_evidence", e])];
  for (const [kind, e] of items) {
    // 숫자가 아닌 값(null·문자열·배열)은 JS 형변환으로 우연히 맞아 보일 수 있어 먼저 걸러 낸다.
    if (typeof e.field !== "string" || typeof e.value !== "number" || !Number.isFinite(e.value)) {
      failures.push({ kind, field: e.field, value: e.value, reason: "값 형식" });
      continue;
    }
    const r = resolve(summary, e.field);
    if (!r.found) failures.push({ kind, field: e.field, value: e.value, reason: "경로 없음" });
    else if (!sameNumber(e.value, r.value)) failures.push({ kind, field: e.field, value: e.value, actual: r.value, reason: "값 불일치" });
  }
  if (result.verdict === "anomaly" && !(result.evidence ?? []).length) failures.push({ kind: "evidence", reason: "이상 판정에 근거가 없다" });
  if (result.verdict === "anomaly" && !(result.hypotheses ?? []).length) failures.push({ kind: "hypotheses", reason: "이상 판정에 의심 원인이 없다" });
  for (const h of result.hypotheses ?? []) {
    if (h.cause === "other" && !String(h.name ?? "").trim()) failures.push({ kind: "hypotheses", reason: "other 원인에 이름이 없다" });
  }
  // 모델이 다음 필드를 문자열 안에 태그째 써 넣는 경우가 있다(eval5 72회 중 3회, 의심 원인이 요약 안에 들어가 비기도 했다).
  if (/<\/?(parameter|summary_line|hypotheses)\b/.test(JSON.stringify(result))) failures.push({ kind: "format", reason: "답 문자열에 도구 인자 태그가 섞였다" });
  return { ok: failures.length === 0, checked: items.length, failures };
}
