# IMP-025 · 대기열 진입 경로의 Next 홉 우회, 진입 1건 CPU 3.04 → 1.08 ms/req

- 슬라이스: `S10`
- 날짜: 2026-10-06
- 유형: 정량(수치)
- 관련 커밋/PR: ADR-024 적용 #343(머지 `1714032`). before 이미지·배치와 같은 상태에서 Ingress·api Service만 동기화
- 벤치 파일: 레포 밖 `artifacts/loadtest/20261005-1440/`. before `s4-alb-1000-r{1,2,3}`, after `s5-alb-1000-r{1,2,3}`(각 `cpu-per-req.json`·`nodecpu15.txt`·`prom-raw/`. `prom-raw/`의 누적 CPU 카운터 원시 샘플로 다시 계산해 같은 값을 확인), 운영 기록 `session.log`

## 1. 상황 (Context)
10만 burst(100,000 users arriving over 10 seconds, target 10,000 req/s)의 첫 병목은 진입 POST 처리 CPU였다([[ADR-023]] 축 ①).
진입 요청은 브라우저 → ALB → web(Next rewrite) → api로 갔다.

## 2. 문제 정의 + 분류
진입 요청 1건이 web(Next 프록시) 홉에서 api와 비슷한 양의 CPU를 쓴다.
- 계층 분류: 네트워크(프록시 홉), 애플리케이션 코드 밖의 요청 경로
- 근거: [[ADR-023]] 실행 순서 ①의 분리 측정. 진입 CPU 중 web 비중이 44.1%(100/s)·48.6%(1,000/s)이고, api 직접 경로는 web 비용이 없다.

## 3. 증상 (측정된 증거)
- 측정 방법: 공개 ALB 경로, 진입 POST constant 1,000/s × 60초, SSE 없음, HPA api 3/3·web 2/2 고정, m6i.large 3대, 파드 배치 api 노드당 1·web 2노드,
  run마다 새 공연(after r2·r3 예외, §7)·리셋, 같은 rate 워밍업 1회(제외) 뒤 판정 3회. CPU-ms/req = 파드별 cadvisor 누적 CPU의 [run 시작 직전, 종료+30초] 증가분 − 직전 3분 유휴 기울기 × 구간, ÷ 완료 요청 수.
- before(ALB → web → api, 판정 3회 중앙값):
  | 지표 | 값 |
  |------|----|
  | 진입 CPU-ms/req(web + api) | 3.037 (3.355 / 2.980 / 3.037) |
  | ㄴ api / web | 1.597 / 1.440 |
  | p95 | 157.5 ms (694 / 157.5 / 97) |
  | 오류 / dropped | 0 / 0 |

## 4. 가설 (원인 추측, 검증 전)
- 가설 1: Next rewrite 프록시 자체가 요청마다 CPU를 쓴다(HTTP 재파싱·연결·헤더 처리).
- 가설 2: web을 거치면 api 쪽 처리도 늘어난다. ①에서 api 비용이 web 경유 1.483 vs 직접 1.078(1,000/s, NLB)이었다.

## 5. 검증 (도구로 확인 → 확정된 원인)
- 사용 도구: ①의 분리 측정(진단 NLB로 web 경유 vs api 직접, 판정식 사전 등록), 이 IMP의 before/after(공개 ALB).
- 확인 결과: 같은 api 작업인데 경로만 달라 생기는 비용이 web 홉 몫(1.4~1.6 ms/req) + api 증가분이다.

## 6. 조치 (해결)
[[ADR-024]]: 대기열 진입(`POST /api/events/{id}/queue/token`)·상태(`GET /api/queue/status`) 두 경로만 ALB가 api로 직접 보낸다. ALB URL rewrite(컨트롤러
transforms)로 `/api`를 떼어 Spring 매핑에 맞추고, 기본 경로를 `/*` ImplementationSpecific으로 맨 뒤에 둬 규칙 순서를 보장한다. api 대상 그룹 헬스체크는 `/readyz`.
나머지 `/api/*`·OAuth·페이지는 그대로 Next. 하네스 k8s 규칙 2·2-b가 허용 목록·rewrite·규칙 순서·overlay 패치를 강제한다.

## 7. 결과 (재측정, before와 동일 조건)
- 측정 방법: 3단계와 같은 칸(이미지 `8a2d3aa`, 같은 파드, 머지 리비전만 동기화해 파드 재시작 없음, 같은 배치). 사전 등록 합격식(단계 2 `step2-prereg.md`):
  대상 CPU-ms/req 중앙값 개선 ≥ T(= max(10%, 2 × before 범위 ÷ before 중앙값) = 24.7%), p95 중앙값 ≤ before × 1.10, 오류·dropped 비증가, 정합성 위반 0.
  "대상 컨테이너"는 web + api 합으로, 이 IMP 측정 전에 ADR-024에 고정했다(ADR-024 커밋 2026-10-06 20:23·20:33 KST, before 측정 22:18 시작).
  api만 따로 보면 T = 2×(1.908−1.541)/1.597 = 46.0%이고 개선은 32.6%로 그 기준에는 미달이다. 이 결과는 web 홉 제거에서 나온 것이다.
- 공연: before 판정 3회와 after 워밍업·r1은 새 공연이다. after r2·r3는 공연이 부족해 단계 2에서 쓴 1764·1766을 재사용했다(사전 등록은 run마다 새 공연).
  리셋 절차가 wait·admitcount·admitexp·seq를 지우고 대기 토큰·사용자 키 TTL이 1800초(재사용은 사용 뒤 약 6.5시간)이며, run 직전 리셋 확인(wait·admitexp 원소 수)은 0이었다.
- 워밍업(판정 제외): before p95 5.4s·dropped 1,246(오류 0), after p95 17ms·오류·dropped 0.
- after 수치 + 변화율:
  | 지표 | before | after | 변화 |
  |------|--------|-------|------|
  | 진입 CPU-ms/req(web + api) | 3.037 | 1.078 (1.078 / 1.083 / 1.053) | −64.5% |
  | ㄴ api | 1.597 | 1.077 | −32.6% |
  | ㄴ web | 1.440 | 0.000~0.001 | - |
  | p95 | 157.5 ms | 15 ms (15 / 18.5 / 14.7) | −90% |
  | 오류 / dropped | 0 / 0 | 0 / 0 | - |
  | 노드 CPU 15초 평균 최대(세 노드) | 0.42~1.00 | 0.26~0.30 | - |
- 판정: 네 조건 모두 충족해 합격. 사후 정합성 검사 6회 모두 위반 없음.
- 반영 확인(운영, `session.log`): ALB 규칙 1·2가 api 대상 그룹(rewrite 포함)·3 `/*`가 web, api 대상 그룹의 대상 3개 healthy. 진입 POST 200 WAITING(응답 헤더에 Next 흔적 없음) → 받은 토큰으로 상태 GET `?token=` 200 WAITING(쿼리 보존).
  공연 상세는 web, `/api/actuator/prometheus` 404, OAuth 시작 302.

## 8. 트레이드오프 / 다음 개선
- 공개 ALB가 api 대상 그룹을 갖는다(두 경로 한정). 설정상 롤링 중 대상 해제 지연·preStop이 진입 요청에도 적용된다. 롤링 중 무중단은 이 측정 범위 밖이다.
- 측정 조건: 1,000/s·60초 정상 상태 측정이다. 10초 burst·10,000/s에서의 요청당 비용은 이 측정 범위 밖이다. before의 p95 편차(97~694ms)가 커서 p95 개선 폭은 참고로 본다.
- 측정 조건: api 2개 + web 1개가 한 노드에 몰린 배치의 첫 before 시도(`s3-alb-1000-*`)는 web 재시작으로 판정에서 제외하고, api 노드당 1·web 2노드 배치로 다시 쟀다.
- 다음: 용량(단계 6). 이 구조에서 api 파드 하나가 감당하는 rate와 다음 병목(Redis `cache.t4g.micro` 등)을 재고 필요 용량을 정한다.

## 9. 배운 점 (면접 답변용 한 줄)
"진입 CPU가 부족하다"를 노드 증설로 풀기 전에 경로별로 나눠 재 보니 프록시 홉이 절반 가까이(web 비중 43~49%: ① 분리 측정 44.1·48.6%, 공개 ALB 100/s 43.2%)를 썼다. 두 경로만 ALB에서 우회해 같은 조건 재측정으로 요청당 CPU를 3.04 → 1.08 ms(−64.5%)로 줄였고, 판정식은 측정 전에 고정했다.
