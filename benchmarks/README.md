# benchmarks/ — 측정 결과 박제

개선 전후의 측정 결과(JSON/요약)를 **그대로 커밋**해 Git 히스토리를 증거로 만든다.
"850ms였다고 주장"이 아니라 "이 커밋의 이 파일에 850ms가 찍혀있음"이 되도록.

## 네이밍 규칙
```
benchmarks/
  <topic>-before.json      # 개선 전 측정 (naive 버전)
  <topic>-after.json       # 개선 후 측정
  <topic>-summary.md       # 두 결과 비교 요약(선택)
```
예: `seat-stock-before.json`, `seat-stock-after.json`

## 저장 관례
1. 개선 착수 **전**에 측정 → before 파일 커밋 (별도 커밋으로 시점 분리 권장).
2. 개선 후 **동일 조건**으로 재측정 → after 파일 커밋.
3. 요약 수치는 해당 `docs/improvements/IMP-XXX.md`에 표로 옮기고, 여기 파일을 링크.

## 측정 종류
- k6: `k6 run --summary-export=benchmarks/<topic>-before.json infra/k6/<scenario>.js`
- 동시성 테스트: 결과 카운트(초과판매 건수 등)를 JSON/MD로 저장
- 쿼리 수: Hibernate statistics 로그 캡처

> 주의: 측정은 **동일 환경/조건**에서. before/after 조건이 다르면 수치가 의미 없음.
