# Feedback Routing (실패 환류 규칙)

검증 실패 시 **고치고 끝내지 않고** 원인을 분류해 재발 방지 위치로 승격한다.
원칙: 정적으로 판별 가능 → 하네스/계약. 실행해야 아는 것 → 테스트. 일회성 → 승격 안 함.

## 1. 실패 원인 분류 → 승격 위치
| 실패 성격 | 승격 위치 | 비고 |
|---|---|---|
| 금지/불필요 의존성 추가 | `contracts/allowed-stack.yaml` | 화이트리스트 갱신 |
| 문서화 안 된 상태값/enum | `contracts/enums.yaml` + 검사 강화 | |
| BE/FE enum·타입 불일치 | `contracts/enums.yaml` / FE `types/contracts.ts` | 양쪽 동시 |
| API path drift(추가/삭제/변경) | `contracts/api.yaml` + FE path fragment | |
| 이벤트 발행/구독 불일치 | `contracts/events.yaml` | publishes ⊇ fe_subscribes |
| 잘못된 에러코드 | `contracts/error-codes.yaml` | |
| 계층 참조 위반 | `contracts/layer-rules.yaml` | |
| 도메인 불변식 누락 | `domain-rules.md` (+ 가능하면 enum 전이로 강제) | |
| 트랜잭션 원자성/동시성 | **통합·동시성 테스트** | 하네스 아님 |
| 응답 의미/실제 동작 변화 | **통합 테스트** | 하네스 아님 |
| 성능 저하 | **performance-rules.md / 부하테스트** | 하네스 아님 |
| 테스트 부족 | 테스트 추가 | |
| 하네스가 못 잡음(false negative) | **harness 스크립트 + 메타 fixture** | 위반 케이스 추가 |
| 일회성 단순 실수 | 승격 안 함 | 하네스 비대화 방지 |

## 2. 승격 판단 기준
- 재발 가능성 있음 + 정적 판별 가능 → 하네스/계약으로
- 재발 가능성 있음 + 실행으로만 확인 → 테스트로
- 재발 가능성 낮음(오타 등) → 그냥 수정

## 3. 하네스가 false negative였던 경우 (메타)
- 해당 위반을 `harness/fixtures/violations/`에 샘플로 추가
- 그 fixture에 대해 하네스가 **실패하는지** 메타테스트로 확인
- 그래야 "하네스도 검증 대상"이 성립
