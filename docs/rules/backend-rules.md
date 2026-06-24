# Backend Rules (Spring Boot, 계층형)

엄격 모드: ★ 표시는 하네스 스크립트가 정적으로 강제(위반 시 CI 실패).

## 1. 계층 구조 — Controller · Service · Repository ★
단방향 의존만 허용:
```
controller → service → repository → (entity)
        ↘ dto              ↘ domain
```
- ★ `controller`는 `repository`를 **직접 import 금지** (반드시 service 경유)
- ★ `repository`는 `service`/`controller`를 import 금지
- ★ entity는 다른 계층을 import 금지
- 비즈니스 로직은 **service에만**. controller는 입출력/검증/매핑만.

## 2. 패키지 구조 (com.flowticket)
```
com.flowticket
 ├─ <slice>/                 # event, queue, seat, order, payment, admin ...
 │   ├─ controller/   XxxController
 │   ├─ service/      XxxService (interface) + XxxServiceImpl
 │   ├─ repository/   XxxRepository (+ querydsl: XxxRepositoryCustom/Impl)
 │   ├─ domain/       Xx (entity), enums
 │   └─ dto/          XxxRequest, XxxResponse
 └─ global/           config, security, error, common(ApiResponse)
```

## 3. 네이밍 ★
- Controller: `XxxController` / 메서드는 동사+리소스
- Service: 인터페이스 `XxxService` + 구현 `XxxServiceImpl`
- Repository: `XxxRepository`
- DTO: 요청 `XxxRequest`, 응답 `XxxResponse` (★ 접미사 강제)
- Entity: 도메인 명사, enum은 `UPPER_SNAKE` 값
- 테스트: `XxxServiceTest`, `XxxControllerTest`

## 4. API 응답 — 공통 래퍼 ★
- 성공: `ApiResponse.ok(data)` → `{ "data": ... }`
- 실패: 전역 `@RestControllerAdvice`에서 `{ "error": { code, message } }`
- ★ 컨트롤러가 엔티티를 직접 반환 금지 (반드시 *Response DTO)
- 에러코드는 `contracts/error-codes.yaml`에 등록된 값만 ★

## 5. 트랜잭션 / 동시성
- `@Transactional`은 **service 레이어**에만. controller/repository 금지.
- 선착순 재고 차감은 Redis 원자연산 우선, DB 정합성은 비관락 또는 버전(@Version).
- 외부호출(KOPIS/PG)은 트랜잭션 경계 **밖**에서.

## 6. 예외 처리
- 도메인 예외는 `BusinessException(ErrorCode)` 하나로 통일, 전역 핸들러가 변환.
- ★ `printStackTrace()` / 빈 catch 금지. 로깅은 slf4j.

## 7. 금지 ★
- `contracts/allowed-stack.yaml` 밖 의존성 추가 금지
- 컨트롤러/엔티티에 비즈니스 로직
- 필드 주입(`@Autowired` 필드) 금지 → 생성자 주입만
- `System.out` 로깅 금지
