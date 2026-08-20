# ADR-016 · Redis 장애 시 토큰 블랙리스트는 fail-open — 읽기는 통과, 쓰기는 실패를 알린다

- 상태: **Accepted** — 읽기 fail-open + 실패 지표, 쓰기는 전파. 회귀 4건
- 날짜: 2026-08-20
- 슬라이스: S01(인증) / 횡단(가용성)
- 관련: [[ADR-007]](관리자 인증 부트스트랩), [[TS-021]](파드당 커넥션 상한),
  [[TS-032]](결정적/일시적 실패 분류)

## 맥락

`TokenBlacklistService.isBlacklisted()`가 Redis를 조회한다. 이 호출은
`JwtAuthenticationFilter` **안에** 있고, 예외 처리가 없었다.

```java
return Boolean.TRUE.equals(redis.hasKey(PREFIX + accessToken));   // 방어 없음
```

`GlobalExceptionHandler`는 필터에서 나는 예외를 잡지 않는다. 그래서 Redis가 죽으면
**Bearer 헤더가 달린 모든 요청이 실패한다.**

범위가 인증 API에 그치지 않는다는 점이 중요하다. 필터는 인가 판정보다 **먼저** 돌고, 토큰이
있으면 경로와 무관하게 블랙리스트를 조회한다. 로그인한 브라우저는 공개 경로에도 `Authorization`을
보내므로, **공연 목록·좌석 조회 같은 Redis와 무관한 읽기까지 함께 끊긴다.**

같은 저장소의 다른 Redis 사용처는 이미 방어돼 있다 — `SeatService.getSeats()`는 캐시 조회를
try/catch로 감싸고 DB로 되돌아간다. **같은 의존성인데 방침이 달랐고, 다르게 정한 흔적이 없었다.**
즉 이것은 선택의 결과가 아니라 **선택하지 않은 결과**였다.

## 결정

**읽기는 fail-open, 쓰기는 fail-loud.**

| 경로 | 방침 | 이유 |
|---|---|---|
| `isBlacklisted()` (읽기) | 실패 시 `false`(통과) + 지표 + WARN | 확인 불가를 차단으로 바꾸지 않는다 |
| `blacklist()` (쓰기) | 예외 전파 | 취소를 기록 못 했는데 "로그아웃됐다"고 답하면 거짓말이다 |

## 근거 — 왜 fail-closed가 아닌가

fail-open이 포기하는 것은 분명하다. **Redis를 못 읽는 동안, 이미 로그아웃된 access token이
남은 TTL 동안 다시 통한다.** 상한은 `jwt.access-token-ttl: 1800` = **30분**이다.

그럼에도 fail-closed를 택하지 않은 이유는, **fail-closed가 그 대가로 아무것도 사지 못하기
때문이다.**

같은 장애 구간에서 `AuthService.logout()`은 **어차피 실패한다.**

```
logout()
  ├─ tokenService.revoke(userId)   → redis.delete(...)   ← Redis
  └─ blacklistService.blacklist()  → redis.set(...)      ← Redis
```

즉 Redis가 죽은 동안에는 **애초에 취소를 기록할 수 없다.** 읽기를 fail-closed로 막아도
"취소할 수 있는 상태"가 보존되지 않는다. 얻는 것 없이 **"취소가 지연된다"를 "서비스가 멈춘다"로
바꿀 뿐이다.**

노출의 성격도 좁다 — 대상은 **이미 그 사용자에게 발급됐던** 토큰이고, 위협이 성립하려면
공격자가 그 토큰을 이미 탈취한 상태여야 한다. 즉 fail-open이 새로 여는 문이 아니라,
**이미 열린 문을 닫는 일이 30분 늦어지는 것**이다.

## 이 판단이 뒤집히는 조건

블랙리스트가 **로그아웃 외의 것**을 막게 되면 계산이 달라진다.

- 침해 계정 강제 차단, 관리자 세션 킬 등 **사건 대응 수단**으로 쓰이는 순간,
  "30분 지연"의 의미가 "공격자에게 30분을 준다"로 바뀐다.
- 그때는 이 ADR을 다시 열어야 한다. 지금 블랙리스트에 쓰는 경로는
  `AuthService.logout()` **하나뿐임을 확인했다.**

## 관측

이 실패는 **사용자에게 보이지 않는다.** 요청은 전부 성공하고 취소만 조용히 안 먹는다.
그래서 지표를 붙였다.

```
auth_blacklist_check_failures_total
```

⚠️ **알림은 없다.** Alertmanager는 수신처가 없어 의도적으로 꺼져 있으므로
(`k8s/monitoring/kube-prometheus-stack.values.yaml`), 이 지표는 **누군가 대시보드를 봐야**
드러난다. 이것은 이 ADR이 해소하지 못한 부분이다.

## 검증

수정 전 상태로 되돌려 회귀 테스트를 돌려 **3/4가 실제로 깨지는 것을 확인했다**(로컬 실행).
특히 필터 테스트가 `RedisConnectionFailureException`을 그대로 밖으로 내보냈다 — 요청 자체가
깨진다는 직접 증거다.

- 조회 실패 시 `false` 반환 / 실패가 계수됨
- **Redis 장애 중에도 Bearer 요청이 체인을 계속 타고 인증 컨텍스트가 설정됨** (필터 수준)
- 쓰기 실패는 전파됨

⚠️ 실제 Redis를 죽여서 확인한 것이 아니라 `RedisConnectionFailureException`을 스텁으로
주입해 재현했다. 클러스터에서 ElastiCache를 내려 확인하지는 않았다.

## 남는 것

- **`TokenService`의 refresh 경로는 이 ADR의 범위가 아니다.** RTR 회전·재사용 탐지는 Redis가
  진실원이라 fail-open이 성립하지 않는다(회전 기록 없이 통과시키면 재사용 탐지가 무력화된다).
  Redis 장애 중 refresh가 실패하는 것은 **의도된 동작으로 남긴다** — 다만 그 판단을 이 문서에서
  내린 것은 아니고, 별도로 검토한 적도 없다.
- 알림 부재(위 §관측).
