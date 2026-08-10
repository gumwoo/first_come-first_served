# Table · events

- 슬라이스: `S02`
- 마이그레이션(단일 진실원): `V4__events.sql`, `V5__events_region.sql`(지역)
- 도메인 규칙: [[domain-rules]] (S02 카탈로그), KOPIS 동기화

## 목적
공연 이벤트 메타데이터. KOPIS OpenAPI에서 동기화한 정보 + 표시용 최소 가격.
좌석 등급별 가격·재고 같은 거래용 필드는 S04(좌석)에서 별도 확장한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | 이벤트 식별자 |
| kopis_id | VARCHAR(50) | Y | | UNIQUE | KOPIS 공연ID(동기화 upsert 키). 시드/수동은 null |
| title | VARCHAR(200) | N | | | 공연명 |
| venue | VARCHAR(200) | Y | | | 공연장 |
| region | VARCHAR(50) | Y | | | KOPIS area(시도, 예 서울특별시). 지역 필터 |
| genre | VARCHAR(50) | Y | | | 장르 |
| poster_url | VARCHAR(500) | Y | | | 포스터 이미지 URL |
| start_date | DATE | Y | | | 공연 시작일 |
| end_date | DATE | Y | | | 공연 종료일 |
| running_time | VARCHAR(50) | Y | | | 관람 시간 |
| age_limit | VARCHAR(50) | Y | | | 관람 연령 |
| status | VARCHAR(20) | N | 'SCHEDULED' | | EventStatus |
| base_price | INTEGER | Y | | | 표시용 최소 가격(원) |
| price_text | TEXT | Y | | | KOPIS `pcseguidance` — 가격 안내 원문 |
| cast_info | TEXT | Y | | | KOPIS `prfcast` — 출연진. 컬럼명이 `cast`가 아닌 이유는 SQL 예약어 |
| synopsis | TEXT | Y | | | KOPIS `sty` — 줄거리 |
| schedule_text | TEXT | Y | | | KOPIS `dtguidance` — 공연시간 안내 원문 |
| detail_synced_at | TIMESTAMP | Y | | | 상세 동기화 시각. NULL(미수집) 또는 오래된 값이면 갱신 대상 |
| created_at | TIMESTAMP | N | now() | | 생성 시각 |
| updated_at | TIMESTAMP | N | now() | | 갱신 시각 |

> `price_text`~`schedule_text`는 KOPIS **상세** API에서만 오는 값이다(V16). 예전에는
> `GET /events/{id}` 요청마다 외부를 호출해 채웠는데, 그러면 외부 호출량이 트래픽의 함수가 되어
> KOPIS 이용 제한(IP당 1초 10회)을 부하 시 약 7배 초과했다. 이제 **동기화 배치가 미리 채우고
> 사용자 요청은 DB만 읽는다**. 아직 못 받은 공연은 이 값들이 NULL이다.

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PRIMARY | id | |
| uq_events_kopis_id | UNIQUE | kopis_id | KOPIS 동기화 멱등(upsert) |
| ix_events_status_start | INDEX | status, start_date | 목록/필터 조회 가속 |
| ix_events_genre | INDEX | genre | 장르 필터 |
| ix_events_region | INDEX | region | 지역 필터 (V5) |

## 관계
- 없음(S02). 이후 seats/orders 등이 event_id로 참조 예정.

## 도메인 규칙 연결
- `kopis_id` UNIQUE ↔ KOPIS 동기화 upsert 멱등성
- 좌석/등급별 가격/재고는 events에 두지 않고 S04 좌석 도메인에서 (KOPIS엔 없음)
- status는 `contracts/enums.yaml` EventStatus와 일치(하네스 검사)
