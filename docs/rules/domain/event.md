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
- 인기 `GET /events/popular`: 조회수 집계 전이라 ON_SALE 최신 N개 임시. 후속 랭킹 지표로 교체 예정.

## 미반영(스키마 부재 — 후속)
- 지역/가격 필터: events에 region/price 세분 필드 없음(S04 좌석·가격에서 확장).
- 관심(찜)/실시간 랭킹: 별도 도메인(조회수·wishlist) 필요.
