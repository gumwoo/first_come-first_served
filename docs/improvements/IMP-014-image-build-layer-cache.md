# IMP-014 · 이미지 빌드 레이어 캐시 — 완전 히트 98.5→5초, 다만 현실 케이스는 미측정

- 슬라이스: 인프라(CI/CD) — 기능 슬라이스 아님
- 날짜: 2026-08-05
- 유형: 정량(워크플로 스텝 시간 실측) — 배포 파이프라인 속도
- 관련 커밋/PR: PR #165
- 벤치 파일: [`benchmarks/image-build-cache-before.json`](../../benchmarks/image-build-cache-before.json)
  → [`benchmarks/image-build-cache-after.json`](../../benchmarks/image-build-cache-after.json)
- 관련: [[IMP-013]](CI 백엔드 잡), `.github/workflows/image.yml`

> ⚠️ **이 문서는 "개선했다"로 끝나지 않는다.** 가장 자주 발생하는 케이스(소스만 변경)를
> **아직 측정하지 못했고**, 그 사실을 §6에 명시한다.

## 1. 상황

외부 사례([모요 CI/CD 개선기](https://tech.moyoplan.com/posts/cicd-improvement))를 읽고
우리 파이프라인에 적용할 것이 있는지 훑었다. 대부분은 스택·규모가 달라 맞지 않았지만
(pnpm/Turbo 모노레포, self-hosted 러너, 머지큐), **한 곳만 캐시가 통째로 비어 있었다** —
이미지 빌드다.

```yaml
docker build -f apps/api/Dockerfile -t "$IMAGE" .   # 캐시 없음
```

## 2. 문제 정의 + 분류

- 계층 분류: **배포 파이프라인 성능**(제품 성능 아님).
- `apps/api/Dockerfile`은 이미 레이어 최적화가 되어 있다 — 의존성 정의를 먼저 복사하고
  `gradle dependencies`를 별도 레이어로 뒀다. **그런데도 매번 느렸다.**
- 원인 가설: **GitHub 러너는 매 실행 빈 Docker 캐시로 시작한다.** Dockerfile이 아무리 잘
  나뉘어 있어도 실행 간에 재사용할 캐시가 없으면 의미가 없다.

## 3. 증상 (측정된 증거)

`build & push` 스텝만 분리해 측정했다(잡 전체에는 checkout·OIDC 인증·ECR 로그인이 섞여 있다).

| 이미지 | 4회 측정 | 중앙값 |
|---|---|---|
| API | 104 / 95 / 98 / 99초 | **98.5초** |
| Web | 61 / 62 / 64 / 63초 | **62.5초** |

## 4. 검증 (레이어별 실측 → 확정 원인)

buildx 로그에서 레이어별 소요시간을 그대로 읽었다. **API 이미지 cold 빌드:**

```
gradle:8.10.2-jdk17 pull        12.3s
eclipse-temurin:17-jre pull      5.7s
RUN apt-get install curl         6.2s
COPY build.gradle.kts            0.2s
RUN gradle dependencies         38.8s   ← 가장 큰 단일 항목
COPY src                         0.2s
RUN gradle bootJar              29.6s
COPY jar                         0.5s
```

**`gradle dependencies` 하나가 38.8초**다. 이 레이어는 `build.gradle.kts`가 바뀌지 않는 한
내용이 같은데, 캐시가 없어 매 빌드가 의존성을 처음부터 받고 있었다 → **원인 확정.**

Web은 구조가 다르다 — `pnpm install` 6.5초 / `pnpm build` 36.6초로, **캐시되는 쪽이 작고
재실행되는 쪽이 크다.** 같은 처방이라도 이득이 작을 것으로 보였다.

## 5. 해결 + 재측정

`docker/setup-buildx-action` + `docker/build-push-action@v6`로 바꾸고 GitHub Actions 캐시를 붙였다.

```yaml
cache-from: type=gha,scope=image-api
cache-to:   type=gha,mode=max,scope=image-api
```

- **`mode=max`가 필수다.** 기본값 `mode=min`은 최종 이미지 레이어만 캐시한다. 우리 비용은
  **멀티스테이지의 builder 단계**에 있으므로 기본값으로는 효과가 없다.
- **`scope`를 api/web으로 나눴다.** 한 캐시 공간을 공유하면 서로를 밀어낸다.

### 측정 결과

| 시나리오 | API | Web | 성격 |
|---|---|---|---|
| **캐시 없음(baseline)** | 98.5초 | 62.5초 | 실측(4회 중앙값) |
| **cold — 캐시 채우는 첫 실행** | **187초** | **109초** | 실측. baseline보다 **느리다**(+88 / +47초) |
| **완전 히트 — 같은 SHA 재실행** | **5초** | **5초** | 실측. **상한값** |
| **소스만 변경** | **미측정** | **미측정** | §6 |

**완전 히트 98.5 → 5초**는 인상적이지만 **일상적인 케이스가 아니다.** 같은 커밋을 다시
빌드하는 상황이라 `bootJar`까지 캐시된 값이다. 이 수치만 들고 "94% 개선"이라고 쓰면 과장이다.

## 6. ⚠️ 아직 측정하지 못한 것 (가장 중요한 케이스)

**이미지 빌드는 대부분 "소스가 바뀌었을 때" 일어난다.** 그게 실제로 몇 초인지 아직 모른다.

측정하지 못한 이유: 측정하려면 `apps/api/src`를 실제로 바꾼 커밋이 필요한데,
**수치를 얻으려고 코드를 바꾸는 것은 측정이 아니라 조작**이다. 다음에 API 소스가 실제로
바뀔 때 측정해 이 문서와 벤치 파일을 갱신한다.

**레이어 분해로 추정할 수는 있다(추정임을 명시한다):**
```
캐시에서 복원   base 이미지 18.0s + apt 6.2s + dependencies 38.8s ≈ 63.0s 분량
다시 실행       bootJar 29.6s
```
즉 **63초 분량의 작업이 캐시로 대체되고 30초가 재실행**된다. 다만 캐시 복원 자체가 공짜가
아니므로(수백 MB를 GHA 캐시에서 내려받는다) 최종 스텝 시간은 29.6초보다 크다.
**baseline 98.5초보다는 줄어들 것으로 보지만, 얼마나인지는 측정 전까지 말할 수 없다.**

## 7. 비용 (정직)

- **cold 빌드가 느려진다.** API +88초, Web +47초. cold는 `build.gradle.kts`/`pnpm-lock.yaml`
  변경, 7일 미사용 만료, 10GB 한도 초과 시 LRU 축출에서 다시 발생한다.
- **GHA 캐시 1,844MB / 235개 항목**을 쓴다(한도 10GB). `mode=max`가 중간 레이어까지 저장해
  항목 수가 많다. 콘텐츠 주소 기반이라 변하지 않은 레이어는 재사용되므로 빌드마다 선형으로
  늘지는 않지만, **한도에 근접하면 축출이 잦아져 cold가 반복될 수 있다** — 모니터링 대상이다.
- **의존 액션이 2개 늘었다**(`setup-buildx-action`, `build-push-action`). `setup-buildx` 자체가
  5~13초를 쓴다 — 캐시 이득에서 차감해야 하는 비용이다.

## 8. 한계

- **가장 흔한 케이스가 미측정이다**(§6). 이 문서의 결론은 그때까지 잠정이다.
- **Web은 이득이 작을 것으로 본다.** 캐시되는 `pnpm install`이 6.5초뿐이고 재실행되는
  `pnpm build`가 36.6초다. cold 페널티(+47초)를 상쇄할지 확인이 필요하다 —
  **Web만 캐시를 빼는 것이 나을 수도 있다.**
- **측정은 GitHub Actions 러너 기준**이라 러너 성능에 따라 분 단위 변동이 있다.
  baseline은 4회를 재 중앙값을 썼지만 cold/warm은 각 1회다.
- **이 변경으로 보안 제약 하나가 드러났다.** OIDC 신뢰 조건을 `ref:refs/heads/main`으로
  좁힌 직후라(PR #164), **브랜치에서 이 워크플로를 시험할 수 없었다.** main에 머지한 뒤에야
  측정할 수 있었다 — 보안 강화의 대가로 얻은 검증 마찰이며, 앞으로 image.yml을 고칠 때마다
  반복된다.
