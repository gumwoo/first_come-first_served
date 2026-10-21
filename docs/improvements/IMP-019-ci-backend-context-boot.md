# IMP-019 · CI 백엔드 9m46s → 3m08s, 컨텍스트 부트를 붙잡던 KafkaAdmin

- 슬라이스: 전반(CI)
- 날짜: 2026-08-16
- 유형: 정량(CI wall-clock 실측), 테스트 인프라
- 관련: [IMP-013](IMP-013-ci-shared-testcontainers.md)(같은 잡의 앞선 개선, 23.5분 → 9.35분),
  [[TS-020]](에러 없이 조용히 멈추는 실패), `apps/api/src/test/.../TestContainers.java`(Kafka 미공유 결정)
- 측정 원본: 이 문서 §3·§7 (GitHub Actions 실행 로그·JUnit XML 아티팩트)

> 흐름: 문제 정의 → 증상(수치) → 가설 → 도구로 검증 → 해결 → 재측정 → 다음 개선.

## 1. 상황 (Context)

CI 백엔드 잡이 약 10분이었다. 이 잡은 [IMP-013](IMP-013-ci-shared-testcontainers.md)에서
한 번 개선했다(23.5분 → 9.35분, 클래스마다 뜨던 컨테이너를 공유로). 이번은 그 두 번째 라운드이고,
컨테이너를 걷어낸 뒤 드러난 다음 층을 다룬다. backend·frontend·E2E는 병렬이라 가장 느린 잡이
전체 CI 시간을 결정하는데, E2E가 3m47s인 반면 backend가 9~10분으로 critical path였다.

원칙은 테스트를 빼지 않고 같은 검증을 더 빨리 끝내는 것이었다. 이 개선으로 빠진 테스트는
없다(193개 그대로).

## 2. 문제 정의 + 분류

- 계층 분류: 테스트 인프라(운영 코드는 바뀌지 않았다).
- 잡이 느리다는 것만 알려져 있었고, 어느 구간이 느린지는 측정으로 먼저 갈라야 했다.

## 3. 증상 (측정된 증거)

### 3-1. 잡·태스크 단위

```
backend 잡 (6표본)   568 572 581 586 592 601      median 586s = 9m46s
└ gradle build 스텝                                551~558s
```

`gradle build` 안을 로그 타임스탬프로 갈랐다.

```
   0s  > Task :compileJava      ┐
   0s  > Task :bootJar          │
   0s  > Task :jar              ├ build 고유 비용 ≈ 4s
   3s  > Task :assemble         │
   0s  > Task :check            ┘
  30s  > Task :testClasses
 501s  > Task :test             ← 91%
```

### 3-2. `:test` 안에서 testcase는 11%

성공 실행의 JUnit XML을 아티팩트로 남겨(#229) 클래스별 시간을 얻었다.

```
:test wall-clock          526s
├─ XML testcase 귀속       58.1s  (11%)   43 suite / 193 테스트
└─ testcase 미귀속        467.9s  (89%)
```

XML은 테스트별 시간만 담으므로, 미귀속 구간(컨테이너·컨텍스트 등)은 따로 측정했다.

### 3-3. 미귀속 468초의 구성

측정 게이트(`-PciTiming`)로 컨텍스트 캐시 통계와 Testcontainers 로그를 한 번 켰다.

```
Spring 컨텍스트 부트 14회   446.3s   ← 미귀속의 95%
컨테이너 기동 15개           18.3s
나머지                        ~3s
```

부트 시간은 두 그룹으로 갈렸다.

```
41초대  9회 → 372.8s   비-Kafka 통합테스트
1~4초   4회 →   8.6s   Kafka 통합테스트
65.2초  1회           첫 부트(일회성 초기화)
```

Kafka를 쓰는 테스트가 빠르고, 안 쓰는 테스트가 느렸다.

## 4. 가설 (검증 전)

비-Kafka 그룹은 `IntegrationTestSupport`가 bootstrap을 연결 불가 주소로 고정한다
(`localhost:59092`, 로컬에 떠 있는 Kafka에 얹히지 않게 하려는 의도). 그런데도 Kafka
클라이언트가 뜨면서 붙지 못해 41초를 쓴다.

## 5. 검증 (도구로 확인 → 확정 원인)

### 5-1. 후보 검증: 컨슈머 재연결 루프

로거별로 집계하니 부트 구간 1,325줄 중 869줄이 `NetworkClient`였다. 컨슈머 재연결 루프를
후보로 보고 `spring.kafka.listener.auto-startup=false`를 넣어 CI를 돌렸다.

```
NetworkClient 로그   20,987 → 13,935줄   (-34%)
컨텍스트 부트 합       446.3s → 443.1s   (-0.7%)
```

로그는 3분의 1이 줄었지만 부트 시간은 거의 그대로였다. 재시도는 CPU를 거의 쓰지 않는 배경
작업이었으므로 이 후보는 기각하고, 테스트 동작(리스너 비활성)만 바꾸는 변경은 되돌렸다.

### 5-2. 마일스톤 타임라인

부트 마일스톤의 절대 시각을 나열했다.

```
+ 0.0s  Starting EventIntegrationTest
+ 0.2s  Bootstrapping Spring Data JPA repositories
+ 0.4s  HikariPool-4 - Start completed
+ 0.5s  HHH000204: Processing PersistenceUnitInfo
+ 0.6s  Initialized JPA EntityManagerFactory     ← DB/JPA 초기화 단계가 이 시점까지 진행
+ 1.4s  Kafka version 3.7.1  (client.id=flowticket-api-admin-N)
+41.4s  Kafka version 3.7.1                      ← 40.0초 단일 공백
+41.4s  Started in 41.443 seconds
```

이 타임라인은 마일스톤 로그의 절대 시각이며, 컴포넌트별 초기화 비용을 분해한 값은 아니다.
DB/JPA 초기화 단계가 +0.6초 시점까지 진행됐고, 그 뒤 40초가 비어 있다.

원인은 `KafkaAdmin`이었다. `KafkaConfig`에 `NewTopic` 빈이 둘 있어
(`order-events`, `order-events.DLT`) Spring Boot가 부팅 시 브로커에 붙어 토픽을 만들려 한다.
붙지 못한 채 재시도하다 약 40초 뒤에 포기한다.

`listener.auto-startup`이 효과가 없던 이유도 여기서 설명된다. 멈추는 주체는 admin
클라이언트다.

## 6. 조치 (해결)

`IntegrationTestSupport`의 `@TestPropertySource`에 한 줄을 추가했다.

```java
"spring.kafka.admin.auto-create=false",
```

넣기 전에 이 프로퍼티가 Boot 3.3.5에 실제로 있는지 확인했다.
`spring-configuration-metadata.json`에서 `spring.kafka.admin.auto-create (Boolean, 기본 true)`.
존재하지 않는 키는 조용히 무시되므로 먼저 확인했다.

Kafka를 실제로 쓰는 테스트 4개는 이 베이스를 상속하지 않아 토픽 생성이 유지된다.

## 7. 결과 (재측정, 동일 조건)

측정 게이트를 끈 뒤(AS-IS와 같은 조건) 다시 쟀다.

| 지표 | AS-IS | TO-BE | 변화 |
|---|---|---|---|
| backend 잡 (median) | **9m46s** (586s) | **3m08s** (188s) | **-68%** |
| 표본 | 568·572·581·586·592·601 | 168·186·188·195·199 | 6표본 / 5표본 |
| 편차(최대−최소) | 33s | 31s | - |

측정 모드 안에서의 내부 지표(조건이 달라 위 표와 섞지 않는다):

| 지표 | 수정 전 | 수정 후 |
|---|---|---|
| 컨텍스트 부트 합(14회) | 446.3s | **41.1s** |
| 41초대 컨텍스트 9개 | 각 41.0~41.8s | **각 0.9~1.8s** |
| `:test` | 526s | 126s |

빠진 테스트는 없다(193개 그대로). 검증 강도는 그대로 두고 얻은 시간이다.

## 8. 트레이드오프 / 다음 개선

- 기각한 후보 넷. 흔히 권장되는 최적화가 이 저장소에서는 표적이 아니었다.

  | 후보 | 근거 |
  |---|---|
  | `gradle build` → `test` | build 고유 비용이 ~4s라 전환 이득이 거의 없고, `assemble`/패키징을 제외할 이유도 없다 |
  | 개별 느린 테스트 최적화 | XML 합이 58.1s. 가장 느린 것도 13s(타임아웃을 일부러 재는 테스트) |
  | Testcontainers 컨테이너 공유 | 기동 총 18.3s. 전부 공유해도 절감 상한 13.8s(:test의 2.6%) |
  | Kafka 리스너 재시도 루프 | 로그 −34%인데 시간 −0.7% |

- Kafka 컨테이너 공유는 기존 결정을 유지했다. `TestContainers` 클래스 주석에 토픽·컨슈머 그룹
  오염으로 기각한 기록이 있고, 실측(11.4s)도 그 판단을 뒷받침했다.
- 컨텍스트는 실측 14개다(코드 기준 추정 8개). 부트가 1초대가 된 지금 14개여도 41초라 우선순위가
  낮다. 줄이려면 테스트별 `@TestPropertySource` 차이를 합쳐야 하는데, 그중 일부는 [[TS-024]]에서
  플래키를 피하려 일부러 분리한 것이다.
- 첫 부트에만 남는 일회성 초기화 비용(측정 모드 기준 63.9s→21.7s)은 1회뿐이라 우선순위를 낮게
  두었다.
- 운영 코드는 바뀌지 않았다. 프로덕션에서는 브로커가 있으므로 토픽 자동 생성이 그대로
  동작한다. 이 변경은 테스트 전용 프로퍼티다.
- 다음 후보는 job 분할이다. backend가 3분대가 되어 E2E(3m47s)와 비슷해졌으므로, 더 줄이려면
  E2E 쪽 critical path를 봐야 한다.

## 9. 배운 점 (면접 답변용)

> CI 백엔드가 10분이라 줄이려 했는데, 흔한 조언(테스트 병렬화·컨테이너 공유·빌드 태스크 축소)이
> 전부 표적이 아니었습니다. 먼저 `gradle build` 안을 갈라 `:test`가 91%임을 확인하고, JUnit
> XML로 테스트 코드는 58초뿐이고 468초가 그 밖이라는 걸 봤습니다. 다시 그 안을 재니
> Spring 컨텍스트 부트 14회가 446초였고, 마일스톤 타임라인을 찍으니 DB/JPA 초기화는
> 0.6초 시점에 끝나 있는데 Kafka admin 클라이언트 구간에서 40초가 통으로 비어 있었습니다.
> `NewTopic` 빈 때문에 부팅 시 토픽을 만들려다 브로커가 없어 40초를 기다린 것이었고,
> 테스트 프로퍼티 한 줄로 9분 46초 → 3분 8초(-68%)가 됐습니다. 테스트는 하나도 빼지 않았습니다.
>
> 재시도 루프 후보는 로그가 34% 줄어도 시간은 0.7%만 줄어 기각했습니다. 로그 줄 수 같은 간접
> 지표보다 시작·종료 시각을 직접 찍는 것이 원인을 가장 빨리 갈랐습니다.

## 10. 원자료

- AS-IS/TO-BE wall-clock: GitHub Actions 실행별 backend 잡 `startedAt`/`completedAt`
- `:test` 내부: `gradle build` 스텝 로그의 `> Task :` 타임스탬프
- testcase 시간: `backend-test-results` 아티팩트(JUnit XML, #229로 성공 시에도 업로드)
- 컨텍스트·컨테이너: `-PciTiming` 게이트(`apps/api/build.gradle.kts`, 기본 꺼짐) 실행 로그
