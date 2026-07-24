# Screens Index — 작업 큐 (단일 진실원)

작업 단위는 **기능 슬라이스**(DB→API→화면→검증 세로 관통).
에이전트는 `status: todo`인 첫 슬라이스를 잡아 끝까지 구현 후 `done`으로 바꾼다.
status: `todo | doing | done | blocked`

## 슬라이스 진행 현황
| id | 슬라이스 | 선행 | status | note |
|----|----------|------|--------|------|
| S01 | 인증(로그인/회원가입) | - | done | BE+FE 구현, 단위테스트. 통합/소셜연동은 후속 |
| S02 | 공연조회(메인/검색/상세) + KOPIS 동기화 | S01 | done | BE+FE+KOPIS 실연동, 단위/통합테스트. 카테고리탭·뷰토글·관심 등 부가요소는 후속 |
| S03 | 대기열 | S02 | done | 발급/승격(정원 Lua)/SSE/UI + IMP-004. QUEUE_NOT_ADMITTED 실차단은 S04 |
| S04 | 좌석·재고(좌석선택/매진/대기만료) | S03 | done | BE(원자 선점·자동시딩·SSE·만료 sweep)+FE(극장식 좌석/매진/만료) + IMP-003 + 상태기계 통합테스트 + TS-001~004. 결제 연결은 S05 |
| S05 | 주문·결제(결제/완료/실패/입금대기) | S04 | done | 주문 생성·Mock 결제 승인+조건부 전이·가상계좌·결제 만료 sweep + 멱등(IMP-008) + 결제 FE/QR + E2E(해피+예외) + Toss 실 PG 연동(카드 결제창·confirm) + 가상계좌 입금 웹훅(secret 대조·멱등) + TS-009/TS-010 |
| S06 | 마이페이지·취소/환불 | S05 | done | 마이페이지 목록(탭·페이징)·상세 + 취소/환불(상태 3분리 CANCELLED/REFUNDED)·기간별 수수료 + 멱등(IMP-009) + 마이페이지 리디자인 + 환불 E2E |
| S07 | 운영(대시보드/내역/이벤트/DLQ/알림) | S05 | doing | 관리자 인증(ADR-007)·대시보드·주문 조회·공연 CRUD·Kafka 이벤트 백본+DLQ(ADR-008)·admin E2E 완료. **알림(alerts) 남음** |
| S08 | 부하테스트·모니터링 | S07 | todo | |

## 슬라이스 ↔ 화면 매핑
### S01 인증
- [login.md](user/login.md) · [signup.md](user/signup.md)

### S02 공연조회
- [main.md](user/main.md) · [search.md](user/search.md) · [event-detail.md](user/event-detail.md)

### S03 대기열
- [queue.md](user/queue.md)

### S04 좌석·재고
- [seat-select.md](user/seat-select.md) · [sold-out.md](user/sold-out.md) · [wait-expired.md](user/wait-expired.md)

### S05 주문·결제
- [payment-card.md](user/payment-card.md) · [payment-easy.md](user/payment-easy.md) · [payment-vbank.md](user/payment-vbank.md)
- [complete.md](user/complete.md) · [failed.md](user/failed.md)

### S06 마이/취소
- [mypage.md](user/mypage.md) · [refund.md](user/refund.md)

### S07 운영
- [dashboard.md](operator/dashboard.md) · [orders.md](operator/orders.md) · [events.md](operator/events.md)
- [event-detail.md](operator/event-detail.md) · [dlq.md](operator/dlq.md) · [alerts.md](operator/alerts.md)

### S08 부하/모니터링
- [loadtest-scenario.md](developer/loadtest-scenario.md) · [loadtest-running.md](developer/loadtest-running.md)
- [loadtest-result.md](developer/loadtest-result.md) · [loadtest-report.md](developer/loadtest-report.md)
- [monitoring.md](developer/monitoring.md)
