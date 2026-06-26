# METRICS — 누적 정량 성과 보드

프로젝트 전체에서 모은 before/after 수치 요약. 각 행은 개선 일지로 링크.
면접/포트폴리오에서 "정량적 성과"를 물으면 이 표 + 커밋된 벤치 JSON을 제시.

## 성능 / 동시성
| 지표 | before | after | 변화 | 근거 |
|------|--------|-------|------|------|
| (예) 초과판매(동시 1k) | 37건 | 0건 | -100% | [IMP-001](IMP-001-...md) |
| (예) 예매 API p95 | 850ms | 210ms | -75% | IMP-00x |
| (예) 처리량 TPS | 120 | 1,400 | +11.6x | IMP-00x |
| (예) 주문조회 쿼리 수 | 51 | 2 | -96% | IMP-002 |

## 하네스 / 품질 게이트 (이미 측정 가능)
| 지표 | 값 | 근거 |
|------|----|----|
| 하네스가 차단하는 위반 유형 | 14종 | harness/meta-test.mjs |
| 계약 검증 항목 | enum/api/event/error/stack/layer/schema/yml-secret/table-doc | contracts/ + harness/ |
| CI 게이트 | meta → backend → frontend | .github/workflows/ci.yml |
| 개발 중 CI가 사전 차단한 위반 | (누적 기록) | PR/CI 로그 |

## 측정 도구
- 부하/성능: k6 (`infra/k6/`, 결과는 `benchmarks/`)
- 동시성: JUnit 동시성 테스트
- 쿼리: Hibernate statistics / p6spy
- 품질: 하네스 메타테스트 + CI 로그
