# Coding Standards (실무 코딩 기준 — 12영역 마스터)

각 항목 태그: **[H]** 하네스 정적 강제 · **[R]** PR 리뷰 체크리스트 · **[T]** 테스트로 검증.
선착순 시스템 특성상 **3(보안)·5(동시성/트랜잭션)·10(테스트)** 에 무게를 둔다.
세부는 backend-rules.md / frontend-rules.md / secure-coding.md / api-rules.md 참조.

## 1. 계층 책임 / 구조
- 컨트롤러는 입출력·검증·매핑만, 비즈니스 로직·조건분기 금지 [R, 거친 H]
- 컨트롤러에서 try/catch로 예외 삼키기 금지 → 전역 핸들러 위임 [H]
- `@Transactional`은 service 패키지에만 (controller/repository 금지) [H]
- 서비스 간 순환 의존 금지 [H]
- 엔티티를 컨트롤러 응답으로 직접 노출 금지 → *Response DTO [H]
- God class / 과도한 책임 집중 금지 [R]

## 2. 예외 처리
- 빈 catch / 예외 삼키기 금지 [H]
- `printStackTrace()` 금지 → slf4j 로거 [H]
- 일반 Exception/RuntimeException 그대로 throw 금지 → BusinessException(ErrorCode) [R]
- 예외 메시지에 민감정보(토큰/비번/카드번호) 금지 [R]
- 사용자에게 스택트레이스/SQL 노출 금지 (전역 핸들러가 정제) [H, T]

## 3. 시큐어 코딩  → secure-coding.md 참조
- 시크릿 하드코딩 금지 → 환경변수 [H]
- SQL/JPQL 문자열 연결 금지 → 파라미터 바인딩 [H]
- 민감정보 로깅 금지 [H 일부, R]
- 비밀번호 단방향 해시(BCrypt), 평문 저장 금지 [R, T]
- 서버측 입력 검증 필수, 소유권/인가 검증 [T]
- 의존성 취약점 스캔(SCA) [H, CI]

## 4. 입력 검증 / 데이터 무결성
- DTO 검증 애너테이션(@NotNull/@Size/@Email/범위) + @Valid [R]
- 경계값·null·빈문자열 처리 [T]
- 금액은 BigDecimal, 돈에 double/float 금지 [H, R]
- 문자열 상태값 대신 enum [H]

## 5. 동시성 / 트랜잭션 (선착순 핵심)
- 재고 차감은 원자적 (Redis 원자연산 / 비관락 / @Version) [T]
- 초과판매 0 보장 — 동시요청 테스트 [T]
- 외부 호출(PG/KOPIS)은 트랜잭션 경계 밖 [R, H 가능]
- 트랜잭션은 클래스 레벨 readOnly + 쓰기 메서드만 @Transactional (backend-rules §5)
- @Transactional self-invocation 함정 주의 [R]
- 결제/재시도 멱등성 (idempotency key) [R, T]

## 6. 데이터베이스 / 성능
- N+1 금지 (fetch join / @EntityGraph) [R, T]
- 페이징 없는 전체 조회 금지 [R, H 가능]
- 조회조건 컬럼 인덱스 고려 [R]
- open-in-view: false [H]
- DDL은 Flyway 버전 관리, 수동 변경 금지 [R]

## 7. API 설계 일관성  → api-rules.md
- 공통 래퍼 / 상태코드 / URL 규약 [H]
- 하위호환 깨는 변경은 계약 변경 PR로 [H]
- 페이징·정렬·필터 규약 통일 [R]

## 8. 네이밍 / 가독성
- 의미 있는 이름, 약어 남발 금지 [R]
- 매직 넘버/문자열 금지 → 상수 [R, H 일부]
- 죽은 코드·주석처리 코드 커밋 금지 [R]
- 메서드는 한 가지 일, 길이 제한 [R, 거친 H]
- 컨벤션 일관(*Request/*Response/*Service) [H]

## 9. 로깅 / 관측성
- 로그 레벨 구분(DEBUG/INFO/WARN/ERROR) [R]
- 구조화 로깅 + correlation id [R]
- 민감정보 마스킹 [H 일부, R]
- 과도한 로깅 금지 [R]

## 10. 테스트
- 핵심 비즈니스 단위 테스트 [R, T]
- 통합 테스트 (Testcontainers: DB/Redis/Kafka) [T]
- 동시성·경합 시나리오 테스트 (선착순) [T]
- 테스트 간 상태/순서 비의존 [R]
- 커버리지 수치보다 의미 있는 케이스 [R]

## 11. 프론트엔드 특화  → frontend-rules.md
- 서버상태(TanStack Query) ↔ 클라상태(Zustand) 분리 [H 일부]
- any/@ts-ignore 남용 금지 [H]
- API 호출은 features/*/api 에서만, 컴포넌트 직접 fetch 금지 [H]
- XSS 방지 (dangerouslySetInnerHTML 지양) [H 가능]
- 토큰 등 민감정보 클라 저장 신중 [R]
- 접근성 + 로딩/에러/빈 상태 처리 [R]

## 12. Git / 협업  → git-pr-rules.md
- 한 커밋 = 한 의도, 의미 있는 메시지 [R]
- 계약 변경은 코드/타입/테스트 함께 [H]
- main 직접 push 금지, PR 게이트 [H, CI]
- 시크릿/대용량 파일 커밋 금지 [H, gitignore]
