# Table · users

- 슬라이스: `S01`
- 마이그레이션(단일 진실원): `apps/api/src/main/resources/db/migration/V1__users.sql`
- 도메인 규칙: [[domain/auth]]

## 목적
회원 계정 저장. 로컬(이메일/비번)·소셜(kakao/naver) 가입을 한 테이블에서 관리한다.

## 컬럼
| 컬럼 | 타입 | NULL | 기본값 | 제약 | 설명 |
|------|------|------|--------|------|------|
| id | BIGINT | N | identity | PK | 회원 식별자 |
| email | VARCHAR(255) | N | | UNIQUE | 로그인 아이디, 전역 유일 |
| password_hash | VARCHAR(60) | Y | | | BCrypt 해시. **소셜 계정은 null** |
| name | VARCHAR(50) | N | | | 이름 |
| phone | VARCHAR(20) | N | | UNIQUE | 휴대폰. 1폰1계정 |
| role | VARCHAR(20) | N | 'ROLE_USER' | | 가입은 항상 ROLE_USER |
| provider | VARCHAR(20) | N | 'local' | | local/kakao/naver |
| created_at | TIMESTAMP | N | now() | | 가입 시각 |

## 인덱스 / 제약
| 이름 | 종류 | 컬럼 | 이유 |
|------|------|------|------|
| (pk) | PRIMARY | id | 식별자 |
| uq_users_email | UNIQUE | email | 이메일 전역 유일성 |
| uq_users_phone | UNIQUE | phone | 1폰1계정(다계정/암표 방지) |

## 관계
- 없음 (S01). 이후 orders.user_id 등이 이 테이블을 참조 예정.

## DDL (읽기용 스냅샷 — 실행본은 마이그레이션 파일)
```sql
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(60),
    name          VARCHAR(50)  NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    provider      VARCHAR(20)  NOT NULL DEFAULT 'local',
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
ALTER TABLE users ADD CONSTRAINT uq_users_phone UNIQUE (phone);
```

## 도메인 규칙 연결
- `email` UNIQUE ↔ 이메일 전역 유일성([[domain/auth]] 1)
- `phone` UNIQUE ↔ 1폰1계정 정책([[domain/auth]] 2)
- `password_hash` NULL 허용 ↔ 소셜 계정 비밀번호 격리
- `role` 기본 ROLE_USER ↔ 가입 권한 고정(권한 상승 방지)
