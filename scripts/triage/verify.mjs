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
    const r = resolve(summary, e.field);
    if (!r.found) failures.push({ kind, field: e.field, value: e.value, reason: "경로 없음" });
    else if (!sameNumber(e.value, r.value)) failures.push({ kind, field: e.field, value: e.value, actual: r.value, reason: "값 불일치" });
  }
  if (result.verdict === "cause" && !(result.evidence ?? []).length) failures.push({ kind: "evidence", reason: "원인 판정에 근거가 없다" });
  return { ok: failures.length === 0, checked: items.length, failures };
}
