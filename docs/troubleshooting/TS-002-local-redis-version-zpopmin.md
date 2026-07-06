# TS-002 · 대기열 승격이 매 틱 실패 — 로컬 구버전 Redis(ZPOPMIN 미지원)

- 슬라이스: `S03`(대기열)
- 날짜: 2026-07-06
- 유형: 인프라(환경) — 코드 수정 없음
- 조치: 6379를 점유하던 네이티브 구버전 Redis 서비스 중지·비활성화 → docker `redis:7.4` 사용
- 관련 문서: [IMP-004](../improvements/IMP-004-queue-admission.md)(정원 원자 승격 — ZPOPMIN 기반 Lua), [TS-001](TS-001-queue-reentry-after-expiry.md)

> 순서: 증상 → 조사 → 근본 원인 → 해결 → 재발 방지.

## 1. 증상
대기열이 "1번 대기 · 내 앞 0명"에서 **입장으로 넘어가지 않고** 무한 대기.
백엔드 로그에는 승격 워커가 매 틱(1.5s) 실패:

```
WARN c.f.queue.service.QueueAdmissionService : [queue] 승격 처리 실패 event=1741: Error in execution
```

(동시에 좌석/가격도 미시딩 상태였으나 그건 별개 원인 — sync 재실행으로 해결.)

## 2. 조사
1. `docker compose exec redis redis-cli KEYS "queue:*"` → **empty**.
   그런데 백엔드는 `active-events`에 1730/1741을 갖고 `/queue/status`가 `rank:2` 정상 반환.
   → **백엔드가 붙은 Redis와, 우리가 들여다본 docker 컨테이너의 Redis가 서로 다른 인스턴스.**
2. `netstat -ano | findstr :6379` → 6379를 **두 PID(4636, 30652)** 가 LISTENING.
   ESTABLISHED 쌍을 보면 백엔드(PID 59100)의 클라이언트 소켓이 **PID 4636**에 연결됨.
3. `tasklist | findstr 4636` → `redis-server.exe` (Windows **서비스**로 상주).
   `Get-Service Redis` → `Running / Automatic`.
4. `application.yml`은 `spring.data.redis.host=localhost:6379`.
   → 즉 6379를 **먼저 잡은 네이티브 구버전 Redis**에 백엔드가 붙고, docker `redis:7.4`(30652)는
   같은 포트를 퍼블리시했지만 백엔드가 실제로 쓰는 건 네이티브 쪽.

## 3. 근본 원인
승격 원자화 Lua(`QueueAdmissionService.ADMIT_LUA`)는 여유 슬롯만큼 대기 head를 pop하는 데
**`ZPOPMIN`** 을 쓴다. `ZPOPMIN`은 **Redis 5.0+** 명령어인데, 6379를 점유한 네이티브 Redis가
그보다 낮은 버전(Windows용 오래된 포트)이라 명령을 몰라 스크립트가 `Error in execution`으로 실패.

- 토큰 발급(ZADD/SET NX/INCR)은 구버전에서도 되므로 "1번 대기"까지는 정상 표시.
- 하지만 **승격만 ZPOPMIN에서 깨져** 아무도 입장 못 함 → 무한 대기.
- CI가 초록이었던 이유: 통합 테스트는 Testcontainers `redis:7.4`로 돌아 ZPOPMIN이 지원됨.
  **로컬 환경 전제가 어긋난 것이지 코드 결함이 아님.**

## 4. 해결 (관리자 권한)
```cmd
net stop Redis
sc config Redis start= disabled     :: 재부팅 후 재기동 방지
```
이후 6379는 docker `redis:7.4`만 응답:
```cmd
docker compose -f infra/docker-compose.yml up -d redis
docker run --rm redis:7.4 redis-cli -h host.docker.internal -p 6379 INFO server | findstr redis_version
:: → redis_version:7.4.x
```
Spring 백엔드 재시작 → 승격 에러 사라지고 입장→좌석 선택으로 정상 진행 확인.

## 5. 재발 방지
- **환경 전제 명시:** 이 프로젝트는 **Redis ≥ 5.0**(ZPOPMIN 의존)을 요구. 로컬은 docker
  `redis:7.4`(infra/docker-compose.yml)를 기준으로 하며, 네이티브 Redis가 6379를 선점하지
  않도록 한다. (CI는 Testcontainers `redis:7.4`)
- **진단 팁:** "CI는 그린인데 로컬만 깨짐" + 특정 명령만 실패(`Error in execution`)면
  **버전/인스턴스 불일치**를 먼저 의심. `netstat`으로 포트 소유 PID를 확인해
  백엔드가 실제로 어느 Redis에 붙는지부터 특정할 것.

## 한계 / 남은 것
- 시작 시 Redis 버전을 검증(assert)하는 헬스체크는 넣지 않음 — 로컬 1회성 문제라 과함.
  다만 확장 시 부팅 시점 버전/명령 지원 점검을 고려할 수 있음.
