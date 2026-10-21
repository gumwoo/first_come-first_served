// 규칙 기반 분류기: 요약 JSON(normalize.mjs) → 원인 범주. AI 분석과 비교하는 기준선이다.
//   node scripts/triage/rules.mjs <run 디렉터리>             → <run>/triage/rule.json
//
// 범주: redis(Redis 지연) | db(DB 쿼리 지연) | app_cpu(앱 CPU 부족) | normal(이상 없음) | undetermined(판단 불가)
//
// 갈래는 "커넥션을 쥔 시간이 어디서 쓰였나"로 나눈다.
// - Redis 명령 완료가 느리면 redis.
// - PostgreSQL 쪽에서 실제로 실행 중인 세션이 많으면(쿼리가 DB에서 오래 걸림) db.
// - Redis·DB가 아닌데 커넥션 점유가 길면 app_cpu(커넥션을 쥔 스레드가 CPU를 못 받는다). 지표 응답 지연은 점유 값이 없을 때만 쓴다.
// 기준값(THRESHOLDS)은 주입 확인 run 4개와 강도를 정하기 전 시험 run으로 정해 고정했다(infra/triage-lab/README.md).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const THRESHOLDS = {
  anomaly_p95_median_ms: 1500, // 예매 p95(세 단계 중앙값)가 이보다 크면 이상
  redis_completion_ms: 5,      // Redis 명령 완료 평균. 앱 CPU 부족 run도 응답 처리가 밀려 2ms대까지 오른다
  db_active_mean: 0.5,         // PostgreSQL 활성 세션 평균. 정상·Redis·앱 CPU run은 0~0.06
  app_usage_ms: 20,            // 커넥션 1회 점유 평균. 정상 5~13ms, 앱 CPU 부족 33~44ms
  app_scrape_rtt_ms: 1000,     // api 지표 응답 최대. 앱 CPU 부족 run에서 856~3,822ms로 흔들리고 Redis·DB run에서도 늘어 점유 값이 없을 때만 쓴다
};

export function classify(s, t = THRESHOLDS) {
  if (!s.validity?.valid) return { verdict: "undetermined", cause: null, reasons: ["측정 무효: " + (s.validity?.reasons ?? []).join(", ")] };
  const p95 = s.booking.p95_median_ms;
  const anomalous = s.booking.http5xx > 0 || (p95 != null && p95 > t.anomaly_p95_median_ms);
  if (!anomalous) return { verdict: "normal", cause: null, reasons: [`예매 p95 중앙값 ${p95}ms ≤ ${t.anomaly_p95_median_ms}ms, 5xx 0`] };

  const redisMs = s.api.redis.completion_ms_mean;
  if (redisMs != null && redisMs > t.redis_completion_ms) {
    return { verdict: "cause", cause: "redis", reasons: [`Redis 명령 완료 평균 ${redisMs}ms > ${t.redis_completion_ms}ms`] };
  }
  if (s.db.active_mean != null && s.db.active_mean > t.db_active_mean) {
    return { verdict: "cause", cause: "db", reasons: [`PostgreSQL 활성 세션 평균 ${s.db.active_mean} > ${t.db_active_mean}`] };
  }
  const usage = s.api.hikari.usage_ms_mean, rtt = s.api.scrape_rtt_ms_max;
  if (usage != null ? usage > t.app_usage_ms : rtt != null && rtt > t.app_scrape_rtt_ms) {
    return { verdict: "cause", cause: "app_cpu", reasons: [`커넥션 점유 평균 ${usage}ms(기준 ${t.app_usage_ms}ms)·지표 응답 최대 ${rtt}ms(기준 ${t.app_scrape_rtt_ms}ms), Redis·DB 지연 기준 미달`] };
  }
  return { verdict: "undetermined", cause: null, reasons: [`예매 p95 중앙값 ${p95}ms로 이상이지만 어느 규칙에도 해당하지 않는다`] };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const dir = process.argv[2];
  if (!dir) { console.error("사용: node scripts/triage/rules.mjs <run 디렉터리>"); process.exit(2); }
  const s = JSON.parse(fs.readFileSync(path.join(dir, "triage", "summary.json"), "utf8"));
  const r = classify(s);
  fs.writeFileSync(path.join(dir, "triage", "rule.json"), JSON.stringify(r, null, 2) + "\n");
  console.log(JSON.stringify(r));
}
