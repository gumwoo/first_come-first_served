# Common · Layout & Routing

## 글로벌 헤더 (모든 화면 공통)
- 좌: **FlowTicket** 로고 (→ `/`)
- 중앙: 통합 검색바 ("공연명, 아티스트, 장소 검색" → `/search?q=`)
- 우: `이벤트`, `예매안내` 메뉴
- 운영자/개발자 화면은 상단에 `운영자콘솔` / `개발자콘솔` 탭 표기

### 인증 상태별 헤더 (DoD)
헤더는 로그인 상태(`authStore`)를 구독해 우측 메뉴를 분기한다:
- [ ] **비로그인**: `로그인` 링크 + 프로필 아이콘(→ /login)
- [ ] **로그인**: `{이름}님`(→ 마이페이지) + `로그아웃` 버튼 + 마이페이지 아이콘
- [ ] 로그아웃 클릭 → `/auth/logout` 호출 + 클라 상태 clear + 홈 이동
- [ ] 앱 로드 시 silent refresh로 상태 복원, `/me`로 사용자명 표시
- [ ] access 만료(401) 시 apiClient가 자동 재발급 후 재시도

## 푸터
- 사업자 정보 / 고객센터 1544-XXXX / 이용약관·개인정보 링크

## 라우팅 맵
| 영역 | 경로 | 화면 |
|---|---|---|
| user | `/` | 메인 |
| user | `/search` | 검색 결과 |
| user | `/login` `/signup` | 로그인·회원가입 |
| user | `/events/:id` | 공연 상세 |
| user | `/events/:id/queue` | 대기열 |
| user | `/events/:id/seats` | 좌석 선택 |
| user | `/events/:id/sold-out` | 매진 |
| user | `/queue/expired` | 대기시간 만료 |
| user | `/orders/:id/pay` | 결제(카드/간편/무통장) |
| user | `/orders/:id/complete` | 예매 완료 |
| user | `/orders/:id/failed` | 예매 실패 |
| user | `/me/orders` | 마이페이지·예매내역 |
| user | `/me/orders/:id/refund` | 예매취소·환불 |
| operator | `/admin` | 운영 대시보드 |
| operator | `/admin/orders` | 주문/예매 내역 |
| operator | `/admin/events` | 이벤트 관리 |
| operator | `/admin/events/:id` | 이벤트 상세 운영 |
| operator | `/admin/dlq` | DLQ 재처리 센터 |
| operator | `/admin/alerts` | 알림/임계치 설정 |
| developer | `/dev/loadtest/new` | 부하테스트 시나리오 설정 |
| developer | `/dev/loadtest/:id/running` | 실행 상태 |
| developer | `/dev/loadtest/:id/result` | 결과 상세 |
| developer | `/dev/loadtest/report` | 리포트/비교 |
| developer | `/dev/monitoring` | 시스템 모니터링 |

## 접근 제어
- user: 비로그인 열람 가능, 예매 흐름은 로그인 필요
- operator/developer: 역할 기반 인가(RBAC). `ROLE_ADMIN`, `ROLE_DEV`
