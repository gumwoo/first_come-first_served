# Table · <table_name>

- 슬라이스: `<S0x>`
- 마이그레이션(단일 진실원): `apps/api/src/main/resources/db/migration/V<n>__<name>.sql`
- 도메인 규칙: `[[domain/<area>]]`

## 목적
<이 테이블이 무엇을 저장하는가 / 왜 필요한가>

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | <식별자> |
| ... | ... | ... | ... | ... | ... |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| pk_<table> | PRIMARY | id | |
| uq_<table>_<col> | UNIQUE | <col> | <왜 유일해야 하는가> |
| ix_<table>_<col> | INDEX | <col> | <조회 패턴> |

## 관계
- <fk 컬럼> → <상대 테이블>.<컬럼> (<관계 설명>) / 없으면 "없음"

## DDL (읽기용 스냅샷 — 실행본은 마이그레이션 파일)
```sql
CREATE TABLE <table> (
  ...
);
COMMENT ON TABLE <table> IS '<설명>';
COMMENT ON COLUMN <table>.<col> IS '<설명>';
```

## 도메인 규칙 연결
- <컬럼/제약>이 어떤 도메인 불변식과 연결되는지 (예: phone UNIQUE ↔ 1폰1계정)
