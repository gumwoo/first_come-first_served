# Domain · S02 공연 조회 / KOPIS 동기화

- 공연 메타는 KOPIS OpenAPI에서 동기화하며, `kopis_id` UNIQUE 기준 upsert(멱등). [T]
- 동기화는 백엔드만 호출(프론트에서 KOPIS 직접 호출 금지). XML 파싱 → `events` upsert.
- 외부 호출(KOPIS)은 트랜잭션 경계 밖에서 수행한다(목록/상세 모두). DB upsert만 트랜잭션 안. [T]
- KOPIS 응답은 byte[]로 받아 UTF-8로 파싱한다(String 변환 시 한글 깨짐 — 회귀방지 테스트 보유). [T]
- KOPIS 조회 기간은 최대 31일(명세 제한). 일배치는 오늘~+30일. [T]
- 공연 상태(`status`)는 `contracts/enums.yaml` EventStatus와 일치(하네스 검사).
  - KOPIS `prfstate` 매핑: 공연중→ON_SALE, 공연완료→CLOSED, 그 외(공연예정 등)→SCHEDULED.
- 조회는 비로그인 열람 가능(예매는 회원 — S03 이후). [T]
- 상세는 KOPIS 상세(관람시간/연령/가격/출연/줄거리)를 진입 시 lazy 호출. 실패/미연동 시 DB 기본만 반환.

## 검색/필터
- 목록 `GET /events`: page/size + genre(정확일치)/status/from~to 필터.
- 검색 `GET /search`: 키워드(title 부분일치) + genre/status 필터, 페이징.

## 조회수 / 랭킹 (Redis)
"인기"와 "실시간"은 **같은 조회 이벤트를 다른 방식으로 집계**한다(둘이 같은 결과면 안 됨). [T]
- **조회 정의**: 상세 진입(`GET /events/:id`)이면 출처(메인/검색/직접)와 무관하게 1회. [T]
  - 중복방지: `view:dedup:{eventId}:{ip}` TTL 60s — 같은 IP 60초 내 재조회는 카운트하지 않는다. [T]
  - 조회 기록은 DB가 아닌 Redis(부수효과). 기록 실패가 조회 응답을 막지 않는다(best-effort). [T]
- **인기 공연 TOP 10 = 누적 조회수**: `event:views:total` ZSET, 감쇠 없음. 장기·안정적. [T]
- **실시간 랭킹 = 지수감쇠 조회수**: `event:views:hot` ZSET. 휘발성. [T]
  - 감쇠: `@Scheduled` 5분마다 전체 score `× 0.8`(반감기 ~15분). score<0.1 → `ZREM`.
  - 결과적으로 "최근 ~30분 조회 추세"를 반영, 인기 TOP과 순서가 다를 수 있다. [T]
- **인기 검색어**: 검색 실행 시 `search:keywords` ZSET `ZINCRBY`. 상위 N 노출. [T]
  - 연관 검색어는 범위 외(목업 유지).

## 미반영(스키마/선행 슬라이스 부재 — 후속)
- 지역/가격 필터: events에 region/price 세분 필드 없음(S04 좌석·가격에서 확장).
- 관심(찜): wishlist 도메인 필요(별도). 현재 상세의 관심 버튼은 비활성(UI only).
- 예매수 기반 인기: 주문(S05) 이후 가능. 현재 랭킹은 조회수 기준.

## 테스트 계획 (구현 시 [T] 항목 검증)
- 단위: 감쇠 적용 후 score 비율(×0.8), score<0.1 제거, dedup TTL 동작.
- 통합(Testcontainers Redis):
  - 같은 IP 60초 내 2회 조회 → 카운트 1.
  - A를 여러 번 조회 → 인기 TOP·실시간 모두 A 상위.
  - A 다수 조회 후 감쇠 1회 → 직후 B를 조회하면 실시간은 B가 A 추월(누적은 A 유지) → **두 랭킹이 달라짐**.
  - 검색어 누적 → 인기검색어 TOP에 반영.
