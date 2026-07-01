# ADR-002 · 대기열(S03) 설계 결정

- 상태: Accepted
- 날짜: 2026-07-01
- 슬라이스: S03(대기열)
- 관련: [[queue]], `contracts/api.yaml`(queue), IMP-004

## 맥락
선착순 오픈 시 좌석/재고(S04)·주문(S05)에 트래픽이 몰리면 뒤단이 무너진다.
대기열로 **정원(capacity) 단위 직렬화**해 뒤단을 보호해야 한다. 설계 선택지가 여럿이라 기록한다.

## 결정
1. **저장은 Redis 전용(DB 테이블 없음).** 대기열은 휘발성 + 고빈도 갱신 → Redis ZSet가 적합.
   순서=ZSet score(진입 seq), rank=ZRANK, total=ZCARD.
2. **승격 = `@Scheduled` 배치 워커**(주기 1.5s). 이벤트드리븐(누가 나갈 때 즉시)보다 단순·예측가능.
3. **슬롯 회수 = 만료 ZSet sweep.** 입장 후 미진행 토큰을 `queue:admitexp` ZSet(score=만료시각)에
   넣고 워커가 `score<=now`를 회수. Redis keyspace notification(외부 설정 의존)보다 단순.
4. **정원 초과 방지 = Redis Lua 원자화.** "정원 확인 → head pop → 카운트 증가"를 단일 스크립트로.
   비원자(GET→비교→POP→INCR)는 동시 실행 시 over-admit 발생(→ IMP-004에서 측정).
5. **1인 1이벤트 1토큰(멱등).** 재요청 시 기존 토큰+현재 순번 반환.
6. **인증 경계**: 토큰 발급은 회원(Bearer). status/SSE는 토큰만으로 접근
   (브라우저 `EventSource`가 Authorization 헤더를 못 붙임 → URL의 token을 비밀값으로).

## 고려한 대안
- **DB 큐 테이블**: 감사/영속엔 유리하나 고빈도 rank 조회·원자 pop에 부적합 → 제외.
- **이벤트드리븐 승격**: 지연 최소지만 구현 복잡·경계조건 많음 → 배치로 충분.
- **keyspace notification 회수**: 정확하나 Redis 설정 의존·이식성↓ → sweep로.

## 결과 / 한계
- 승격 지연 ≤ 워커 주기(1.5s). ETA는 추정치(처리속도 변동).
- SSE는 단일 서버 가정(emitter 로컬). 다중 서버는 Redis Pub/Sub 필요 → ADR-003/S08에서 다룸.
- 봇 다계정 대기까지는 못 막음(계정당 1토큰만 보장).

## 뒤집는 조건
- 다중 서버 스케일아웃 → 승격/SSE를 Pub/Sub 기반으로(별도 ADR).
- 승격 지연이 문제되면 이벤트드리븐 승격 재검토.
