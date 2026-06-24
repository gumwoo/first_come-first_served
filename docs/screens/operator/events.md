# O-EVENTS · 이벤트 관리

- ref: `assets/screens/운영자_이벤트 관리.png`
- route: `/admin/events`
- slice: S07

## 목적
공연/이벤트 등록·운영·종료 관리. KOPIS 동기화 결과도 여기 노출.

## 사용 API
- `GET /admin/events` / `POST /admin/events` / `POST /admin/sync/kopis`

## 화면 요소 (DoD 체크리스트)
- [ ] 기간/상태 필터 + 검색
- [ ] 상단 집계(전체/운영중/오픈예정/종료)
- [ ] 이벤트 테이블(포스터/제목/장소/기간/예매기간/가격/상태/예매율)
- [ ] "새 이벤트 등록" / "KOPIS 동기화" 버튼
- [ ] 페이지네이션

## 연결
- 행 → /admin/events/:id
