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
- `@Transactional`은 **service 레이어**에만 ★. controller/repository 금지.
- **기본 패턴**: 서비스 클래스에 `@Transactional(readOnly = true)`를 걸고,
  쓰기 메서드에만 `@Transactional`을 개별로 오버라이드한다.
  → 조회는 자동 readOnly, 쓰기 지점이 코드에 드러남. (AOP 일괄 적용보다 권장)
  ```java
  @Service
  @Transactional(readOnly = true)
  public class OrderService {
      public OrderResponse get(Long id) { ... }      // readOnly 상속
      @Transactional
      public OrderResponse create(...) { ... }       // 쓰기만 명시
  }
  ```
- 외부호출(KOPIS/PG)은 트랜잭션 경계 **밖**에서. 커넥션 잡은 채 외부 대기 금지.
- 선착순 재고 차감은 Redis 원자연산 우선, DB 정합성은 비관락 또는 버전(@Version).
- `@Transactional` self-invocation(같은 빈 내부 호출) 함정 주의.
- `private` 메서드에 `@Transactional` 금지 ★ (프록시 미적용으로 트랜잭션이 안 걸림).

## 5-3. JPA 엔티티 ★
- `@Enumerated`는 반드시 `EnumType.STRING` ★ (기본 ORDINAL은 enum 순서 변경 시 DB 값 어긋남).
- 엔티티에 Lombok `@Data`/`@EqualsAndHashCode` 금지 ★ → `@Getter` 등 사용
  (프록시·연관관계에서 equals/hashCode 오작동).
- Flyway 마이그레이션 버전 번호 중복 금지 ★ (같은 `V6__` 둘 → 새 번호 사용).

## 5-1. 컨트롤러 금지사항 ★
- 컨트롤러 내 `try/catch`로 비즈니스 예외 처리·삼키기 금지 → 전역 핸들러 위임
- 컨트롤러에 비즈니스 로직/조건분기 금지 (service로)
- `printStackTrace()` / `System.out`·`System.err` 금지 → slf4j

## 5-2. 인젝션/시크릿 ★ (secure-coding.md)
- 문자열 연결로 SQL/JPQL 생성 금지 → 바인드 파라미터
- 시크릿(키/비번/secret) 하드코딩 금지 → 환경변수

## 6. 예외 처리
- 도메인 예외는 `BusinessException(ErrorCode)` 하나로 통일, 전역 핸들러가 변환.
- ★ `printStackTrace()` / 빈 catch 금지. 로깅은 slf4j.
- 일반 `Exception`/`RuntimeException`을 그대로 throw 금지 → 의미 있는 도메인 예외. [R]

## 6-1. 입력 검증 [R, T]
- 모든 요청 DTO는 Bean Validation 사용(`@NotNull`/`@NotBlank`/`@Size`/`@Email`/`@Positive` 등).
- 컨트롤러 파라미터에 `@Valid`(또는 `@Validated`) 강제. 검증 실패는 전역 핸들러가
  `VALIDATION_ERROR`로 변환.
- 클라 검증을 신뢰하지 않고 **서버측 검증을 단일 진실원**으로 둔다.
- 금액·수량은 적절한 타입(돈은 `BigDecimal`, `double`/`float` 금지).

## 6-2. Optional / null 처리 [R]
- 레포지토리 단건 조회는 `Optional` 반환 → `.orElseThrow(() -> new BusinessException(...))`.
  `Optional.get()` 직접 호출 금지.
- 메서드에서 `null` 반환 지양 → 없으면 예외, 컬렉션은 **빈 컬렉션** 반환.
- 외부/DB 값의 null 가능성은 경계에서 처리.

## 6-3. JPA / 영속성 [R, T]
- 연관관계 `fetch`는 기본 **LAZY**(`@ManyToOne`/`@OneToOne` 포함, EAGER 금지).
- 조회 시 N+1 방지: fetch join / `@EntityGraph`. 페이징과 컬렉션 fetch join 혼용 주의.
- 양방향 연관관계는 연관관계 편의 메서드로 양쪽 동기화. 무한루프 유발하는
  `toString`/`equals`/`hashCode`에 연관 엔티티 포함 금지.
- 엔티티 `equals/hashCode`는 식별자 기반(또는 비즈니스 키), 가변 필드 전체 사용 금지.
- 페이징 없는 전체 조회 금지(목록은 `Pageable`).
- 스키마 변경은 Flyway 마이그레이션으로만(`ddl-auto: validate`).

## 6-4. 로깅 [R]
- 로그 레벨 구분(DEBUG/INFO/WARN/ERROR). 정상 흐름에 ERROR 남발 금지.
- 민감정보(비밀번호/토큰/카드번호/주민번호) 로깅 금지·마스킹. [H 일부]
- 가능하면 correlation id로 요청 추적.

## 7. 금지 ★
- `contracts/allowed-stack.yaml` 밖 의존성 추가 금지
- 컨트롤러/엔티티에 비즈니스 로직
- 필드 주입(`@Autowired`) 금지 → 생성자 주입만 ★ (이제 하네스가 강제)
- SecurityConfig에서 `anyRequest().permitAll()` / `"/**"` permitAll 금지 ★ (전체 개방 방지)
- `System.out` 로깅 금지
