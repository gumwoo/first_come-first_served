// AI 원인 분석: 요약 JSON → 원인 후보·근거·반대 지표·확인할 항목(Claude Messages API).
//
// 경계
// - 입력은 normalize.mjs의 요약 JSON뿐이다. 원자료·문서·라벨은 보내지 않는다.
// - 출력은 도구 하나(report_diagnosis, strict)로 받는다. tool_choice는 auto + 프롬프트 지시다.
//   Haiku 5.5는 강제 도구 호출을 받지만 그때는 생각 없이 바로 호출하고, Sonnet 5.5는 400으로 거부한다.
//   temperature·top_p·top_k는 두 모델 모두 보내지 않는다(마이그레이션 문서의 제거 대상). 도구를 부르지 않은 응답은 실패로 처리한다.
// - 근거 수치마다 요약 JSON의 필드 경로를 달게 하고, verify.mjs가 원자료 요약과 대조한다.
// - 설정 변경·조치를 실행하거나 지시하지 않는다. 후보와 근거, 확인할 항목만 낸다(ADR-029).
//
// 키는 환경변수 ANTHROPIC_API_KEY로만 받는다. 모델은 TRIAGE_MODEL(기본 claude-haiku-5-5).
// 구간 집계 수치 약 35개와 초 단위 시계열(표본 8~17개)을 읽고 원인 3범주 중 고르는 일이라 Haiku를 기본으로 둔다.
// 수치를 잘못 옮기면 verify.mjs가 걸러 판단 불가가 된다.

export const PROMPT_VERSION = "triage-prompt/v4";
export const DEFAULT_MODEL = "claude-haiku-5-5";
export const CAUSES = ["redis", "db", "app_cpu"];

export const TOOL = {
  name: "report_diagnosis",
  description: "예매 묶음 run의 원인 진단을 보고한다. 응답마다 반드시 한 번 호출한다.",
  strict: true,
  input_schema: {
    type: "object",
    additionalProperties: false,
    required: ["verdict", "candidates", "evidence", "counter_evidence", "unchecked", "next_checks"],
    properties: {
      verdict: { type: "string", enum: ["cause", "normal", "undetermined"], description: "cause: 원인 후보가 근거로 뒷받침됨, normal: 이상 없음, undetermined: 근거가 부족하거나 서로 모순됨" },
      candidates: {
        type: "array",
        description: "원인 후보, 가능성이 높은 순. verdict가 cause가 아니면 빈 배열이어도 된다.",
        items: {
          type: "object", additionalProperties: false, required: ["cause", "confidence", "rationale"],
          properties: {
            cause: { type: "string", enum: CAUSES },
            confidence: { type: "string", enum: ["high", "medium", "low"] },
            rationale: { type: "string" },
          },
        },
      },
      evidence: {
        type: "array",
        description: "판단을 뒷받침하는 관측. 수치는 반드시 입력 JSON에 있는 값을 그대로 쓰고 경로를 단다.",
        items: {
          type: "object", additionalProperties: false, required: ["claim", "field", "value"],
          properties: {
            claim: { type: "string" },
            field: { type: "string", description: "입력 JSON 경로. 예: api.redis.completion_ms_mean, series[3].tomcat_busy" },
            value: { type: "number" },
          },
        },
      },
      counter_evidence: {
        type: "array",
        description: "1순위 후보와 맞지 않는 관측. 없으면 빈 배열.",
        items: {
          type: "object", additionalProperties: false, required: ["claim", "field", "value"],
          properties: { claim: { type: "string" }, field: { type: "string" }, value: { type: "number" } },
        },
      },
      unchecked: { type: "array", items: { type: "string" }, description: "입력에 없어 확인하지 못한 데이터" },
      next_checks: { type: "array", items: { type: "string" }, description: "사람이 다음에 확인할 지표·실험만 적는다" },
    },
  },
};

export const SYSTEM = `너는 부하 테스트 결과의 초기 원인 분석을 돕는다. 사람이 최종 판단한다.
답은 반드시 report_diagnosis 도구를 한 번 호출해 낸다. 도구 밖에 따로 글을 쓰지 않는다.
입력은 입장자 100명이 같은 순간 예매(좌석 선점·주문·결제)를 시작한 run 하나의 요약 JSON이다.
구성: Spring Boot api 1개(HikariCP 풀 5, Tomcat), PostgreSQL, Redis(입장 검증·토큰 블랙리스트·좌석 알림 발행).
원인 범주는 세 가지다: redis(Redis 응답 지연), db(PostgreSQL 쿼리 지연), app_cpu(api 컨테이너 CPU 부족).

규칙
- 입력 JSON에 있는 수치만 쓴다. 근거마다 필드 경로와 값을 그대로 단다. 계산한 값을 새로 만들지 않는다.
- 근거에는 숫자 필드만 쓴다(validity 같은 참·거짓 필드는 근거로 쓰지 않는다).
- 예매 지연이 크지 않고 오류가 없으면 normal로 답한다.
- 지표가 서로 모순되거나 어느 범주도 뒷받침되지 않으면 undetermined로 답한다. 억지로 고르지 않는다.
- 1순위 후보와 맞지 않는 관측이 있으면 counter_evidence에 적는다.
- 설정 변경을 지시하지 말고, 확인할 지표나 실험만 next_checks에 적는다.

필드 뜻
- booking.p95_ms: 단계별 p95(ms), booking.p95_median_ms: 세 단계 p95의 중앙값
- api.cpu_cores_mean: 구간 평균 api CPU 코어 수(할당 코어는 2개, 유휴 구간이 섞여 낮게 나온다)
- api.cpu_cores_max: 표본 사이 api CPU 최댓값(코어)
- api.cgroup.throttled_period_ratio: 구간 동안 api 컨테이너가 CPU 할당량에 막힌 주기 비율(0~1), throttled_period_ratio_max: 초 단위 최댓값.
  할당량에 막힌 컨테이너는 CPU를 더 쓰지 못해 CPU 사용량(cpu_cores_*)이 오히려 낮게 보인다. CPU 부족은 사용량보다 이 비율로 판단한다.
  앱이 CPU를 못 받으면 Redis 응답 처리와 트랜잭션도 함께 늦어져 Redis·DB 지표가 같이 오를 수 있다
- api.hikari.usage_ms_mean: 커넥션 1회 점유 평균, acquire_ms_mean: 커넥션 획득 대기 평균.
  점유 평균은 Redis를 부르지 않는 주문·결제의 커넥션 사용까지 합친 평균이다. Redis 지연과 크기를 직접 비교하지 말고 정상 기준 대비 얼마나 늘었는지 본다
- api.redis.completion_ms_mean: Redis 명령 완료 평균(ms)
- api.scrape_rtt_ms_max: 지표 응답 최대(ms). 앱이 CPU를 못 받으면 함께 늘어난다
- db.active_mean: PostgreSQL에서 실행 중인 세션 평균, db.idle_in_tx_mean: 트랜잭션 안에서 앱 쪽 작업을 기다리는 세션 평균
- db.active_wait_events: 실행 중 세션의 대기 이벤트 표본 수(CPU는 대기 없이 실행 중)
- db.wal_sync_ms_per_op: WAL 동기화 1회 평균(ms), db.wal_window_s: 그 측정 구간(예매 시작 기준 초)
- disk: 측정 대상과 분리된 디스크 프로브(측정 환경 판정용, 이미 유효로 판정된 run만 들어온다)
- series: 예매 시작 기준 초(s)별 api 지표, scrape_rtt_ms는 그 표본의 지표 응답 시간

정상 기준(같은 구성·같은 부하에서 장애를 넣지 않은 run 9회의 범위, cgroup 항목은 그중 6회)
- booking.p95_median_ms 293~1069
- api.redis.completion_ms_mean 0.31~0.84
- api.hikari.usage_ms_mean 4.7~21.5, api.hikari.acquire_ms_mean 56~253
- api.tomcat_busy_max 1~69, api.cpu_cores_max 0.76~1.76, api.scrape_rtt_ms_max 69~1312
- api.cgroup.throttled_period_ratio 0~0.02, api.cgroup.throttled_period_ratio_max 0~0.18
- db.active_mean 0~0.41, db.idle_in_tx_mean 0.06~0.28, db.wal_sync_ms_per_op 0.73~16.98
WAL 동기화는 호스트 디스크 상태에 따라 정상 run에서도 크게 흔들린다. 기준 범위를 벗어난 정도를 근거로 쓰고, 범위 근처의 값은 이상으로 단정하지 않는다.`;

export function buildRequest(summary, model = process.env.TRIAGE_MODEL || DEFAULT_MODEL) {
  return {
    model,
    max_tokens: 16000, // 생각(adaptive thinking) 토큰도 여기에 포함된다
    output_config: { effort: "medium" },
    system: SYSTEM,
    tools: [TOOL],
    tool_choice: { type: "auto" },
    messages: [{ role: "user", content: `요약 JSON:\n${JSON.stringify(summary)}` }],
  };
}

// client를 넘기지 않으면 SDK를 이때만 불러온다(테스트·CI는 의존성 없이 돈다).
export async function analyze(summary, { client, model } = {}) {
  if (!client) {
    if (!process.env.ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY 환경변수가 필요하다");
    const { default: Anthropic } = await import("@anthropic-ai/sdk");
    client = new Anthropic();
  }
  const req = buildRequest(summary, model);
  const res = await client.messages.create(req);
  const call = (res.content ?? []).find((c) => c.type === "tool_use" && c.name === TOOL.name);
  if (!call) {
    const why = { max_tokens: "출력 한도에 걸렸다", refusal: "모델이 응답을 거절했다" }[res.stop_reason] ?? "도구를 호출하지 않았다";
    throw new Error(`응답에 ${TOOL.name} 호출이 없다: ${why}(stop_reason=${res.stop_reason})`);
  }
  return {
    result: call.input,
    model: res.model ?? req.model,
    prompt_version: PROMPT_VERSION,
    usage: { input_tokens: res.usage?.input_tokens ?? null, output_tokens: res.usage?.output_tokens ?? null },
  };
}
