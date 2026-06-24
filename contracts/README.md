# contracts/ — 기계가 읽는 기대 계약

이 폴더는 **사람용 문서(docs/)와 분리된, 하네스 스크립트가 직접 파싱**하는
단일 진실원이다. 코드(컨트롤러/enum/이벤트/import)에서 추출한 실제값과
이 파일들을 diff 하여 불일치 시 CI를 실패시킨다.

| 파일 | 검사 대상 | 추출 소스 |
|---|---|---|
| `allowed-stack.yaml` | 허용 의존성 | build.gradle.kts / package.json |
| `enums.yaml` | 도메인 enum 값·전이 | BE enum / FE types/contracts.ts |
| `api.yaml` | endpoint (method,path) | `@*Mapping` 컨트롤러 |
| `events.yaml` | Kafka 발행 ↔ FE 구독 | producer / FE 구독 상수 |
| `error-codes.yaml` | 에러코드 | ErrorCode enum / 예외 |
| `layer-rules.yaml` | 계층 import 경계 | import 문 |

## 변경 규칙 (중요)
이 파일들은 **단독으로 바꾸지 않는다.** 계약 변경은 항상
`docs/rules/git-pr-rules.md §3`의 "계약 변경 PR 체크리스트"에 따라
코드·FE 타입·테스트·문서와 **같은 PR**에서 함께 수정한다.

→ 하네스는 변경을 막는 게 아니라, 계약 변경을 명시적으로 드러내는 장치다.

## 사용 (하네스 스크립트가 호출)
- 백엔드: `./gradlew harnessCheck`
- 프론트: `pnpm harness`
- 메타: 위반 fixture에 대해 하네스가 실패하는지 확인
