# IMP-012 · KOPIS 동기화 건별 존재 확인 — 배치 조회로 200→1

- 슬라이스: `S02`(공연조회·KOPIS 동기화)
- 날짜: 2026-08-01
- 유형: 정량(쿼리 수 측정) — 성능/DB 부하
- 관련 커밋/PR: PR #143
- 벤치 파일: [`benchmarks/kopis-upsert-queries-before.json`](../../benchmarks/kopis-upsert-queries-before.json)
  → [`benchmarks/kopis-upsert-queries-after.json`](../../benchmarks/kopis-upsert-queries-after.json)
- 관련: [[IMP-005]](KOPIS 수집 커버리지), [[ADR-002]]

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 한계.

## 1. 상황
KOPIS 동기화는 수집한 공연을 `kopis_id` 기준으로 upsert한다(멱등). IMP-005로 수집 범위를 넓히면서
한 번에 다루는 항목이 100건 → 1,900건대까지 늘었는데, upsert 루프는 항목 단위 그대로였다.

## 2. 문제 정의 + 분류
- 계층 분류: **성능/DB 부하**(정합성 문제 아님).
- 루프 안에서 항목마다 `eventRepository.findByKopisId(...)`를 호출해 **존재 확인 SELECT가 건수만큼** 나갔다.
- 연관관계 지연로딩에서 오는 전형적인 JPA N+1과 모양은 다르지만, **건별 조회로 쿼리가 반복되는**
  같은 부류의 문제다.
- 부수 위험: KOPIS 응답에 같은 `kopisId`가 중복으로 오면 한 배치에서 INSERT가 두 번 나가
  **UNIQUE 위반**이 될 수 있었다.

## 3. 증상 (측정된 증거)
- 측정: `KopisUpsertQueryCountTest.IMP012_기존여부_확인_쿼리수_before_after` — Hibernate 통계
  (prepare statement count)로 **실제 쿼리 수를 직접 카운트**(추정 아님).
- before:
  | 지표 | 값 |
  |------|----|
  | 항목 수 | 200 |
  | 존재 확인 SELECT | **200** |
  | 항목당 쿼리 | **1** |

## 4. 가설 (검증 전)
- 존재 확인은 항목마다 독립적으로 물을 필요가 없다. **키 집합을 한 번에 조회**해 메모리에서 분기하면
  쿼리 수가 배치 크기와 무관하게 상수가 된다.

## 5. 검증 (도구로 확인 → 확정 원인)
- 도구: Testcontainers(Postgres) + Hibernate `Statistics`. 같은 데이터셋에 naive 루프와 배치 조회를
  각각 실행해 쿼리 수를 비교.
- 결과: naive 200회 / 배치 1회 → 반복 조회가 원인임을 수치로 확정.

## 6. 해결 + 재측정
1. `EventRepository.findAllByKopisIdIn(Collection<String>)` 추가.
2. `KopisUpserter.upsertAll`을 **① 유효 항목 필터 + 배치 내 kopisId 중복 제거(뒤 값 우선) → ② 배치 조회
   1회로 `Map<kopisId, Event>` 구성 → ③ 메모리에서 신규/기존 분기**로 재구조화.
- after:
  | 지표 | 값 |
  |------|----|
  | 항목 수 | 200 |
  | 존재 확인 SELECT | **1** |
  | 항목당 쿼리 | **0** |

**존재 확인 쿼리 200 → 1**(배치 크기와 무관하게 상수). 덤으로 **배치 내 중복 kopisId가 UNIQUE 위반을
일으키던 경로**도 닫혔다(같은 배치에서는 한 번만 반영, 뒤에 온 값으로 갱신).

## 7. 한계
- **INSERT/UPDATE 자체는 여전히 건별**이다. 이번 개선은 "존재 확인"만 상수화했다. 쓰기까지 줄이려면
  JDBC batch insert 또는 네이티브 `INSERT ... ON CONFLICT`가 필요하다 — 현재 규모에선 불필요.
- **동시 동기화 경쟁은 코드로 흡수하지 않았다.** 조회~저장 사이에 다른 실행이 같은 `kopisId`를 넣으면
  UNIQUE 위반이 날 수 있다(배치 조회로 그 간격은 오히려 넓어진다). 다만 **PR #142에서 자동·수동 동기화가
  같은 분산 락(`kopis-sync`)을 공유**하도록 바꿔 현실적인 동시 실행 경로는 닫혔다.
  - 예외를 잡아 재조회·갱신으로 흡수하는 방식은 **의도적으로 쓰지 않았다**: JPA에서 flush 시점의 제약
    위반은 트랜잭션을 rollback-only로 만들어, 잡고 계속 진행하면 커밋에서 다시 실패한다. 진짜로 흡수하려면
    네이티브 upsert(`ON CONFLICT`)로 가야 한다 → 동시 동기화가 실제 요구가 될 때 재검토.
- **처리 건수(`synced`)의 의미**는 여전히 "처리한 항목 수"(신규+갱신+무변경)다. `inserted`/`updated`
  분리는 응답 계약 변경이라 별도 과제.
- 측정은 **쿼리 수**이지 실행 시간이 아니다. 시간 기준 개선은 실 KOPIS 데이터·클라우드 DB에서 별도 측정 대상.
