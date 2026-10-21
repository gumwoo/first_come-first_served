# IMP-014 · 이미지 빌드 레이어 캐시, 소스만 변경 98.5→78초(중앙값)

- 슬라이스: 인프라(CI/CD), 기능 슬라이스 아님
- 날짜: 2026-08-05 (§6 실측 추가: 2026-08-06)
- 유형: 정량(워크플로 스텝 시간 실측), 배포 파이프라인 속도
- 관련 커밋/PR: PR #165 (§6 갱신: PR #172)
- 벤치 파일: [`benchmarks/image-build-cache-before.json`](../../benchmarks/image-build-cache-before.json)
  → [`benchmarks/image-build-cache-after.json`](../../benchmarks/image-build-cache-after.json)
- 관련: [[IMP-013]](CI 백엔드 잡), `.github/workflows/image.yml`

> 요약: 소스만 바뀐 API 이미지 빌드가 중앙값 98.5→78초(-21%)로 줄었다. 개별 빌드는 63~103초에
> 분포한다(§6, n=4).

## 1. 상황

외부 사례([모요 CI/CD 개선기](https://tech.moyoplan.com/posts/cicd-improvement))를 읽고
우리 파이프라인에 적용할 것이 있는지 훑었다. 대부분은 스택·규모가 달라 맞지 않았다
(pnpm/Turbo 모노레포, self-hosted 러너, 머지큐). 캐시가 통째로 비어 있는 곳은 이미지 빌드 한 곳이었다.

```yaml
docker build -f apps/api/Dockerfile -t "$IMAGE" .   # 캐시 없음
```

## 2. 문제 정의 + 분류

- 계층 분류: 배포 파이프라인 성능(제품 성능 아님).
- `apps/api/Dockerfile`은 이미 레이어 최적화가 되어 있다. 의존성 정의를 먼저 복사하고
  `gradle dependencies`를 별도 레이어로 뒀다. 그런데도 매번 느렸다.
- 원인 가설: GitHub 러너는 매 실행 빈 Docker 캐시로 시작한다. 실행 간에 재사용할 캐시가 없으면
  Dockerfile의 레이어 분리가 효과를 내지 못한다.

## 3. 증상 (측정된 증거)

`build & push` 스텝만 분리해 측정했다(잡 전체에는 checkout·OIDC 인증·ECR 로그인이 섞여 있다).

| 이미지 | 4회 측정 | 중앙값 |
|---|---|---|
| API | 104 / 95 / 98 / 99초 | 98.5초 |
| Web | 61 / 62 / 64 / 63초 | 62.5초 |

## 4. 검증 (레이어별 실측 → 확정 원인)

buildx 로그에서 레이어별 소요시간을 그대로 읽었다. API 이미지 cold 빌드:

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

`gradle dependencies` 하나가 38.8초다. 이 레이어는 `build.gradle.kts`가 바뀌지 않는 한
내용이 같은데, 캐시가 없어 매 빌드가 의존성을 처음부터 받고 있었다. 이것으로 원인을 확정했다.

Web은 구조가 다르다. `pnpm install` 6.5초 / `pnpm build` 36.6초로, 캐시되는 쪽이 작고
재실행되는 쪽이 크다. 같은 처방이라도 이득이 작을 것으로 보았다.

## 5. 해결 + 재측정

`docker/setup-buildx-action` + `docker/build-push-action@v6`로 바꾸고 GitHub Actions 캐시를 붙였다.

```yaml
cache-from: type=gha,scope=image-api
cache-to:   type=gha,mode=max,scope=image-api
```

- `mode=max`가 필요하다. 기본값 `mode=min`은 최종 이미지 레이어만 캐시한다. 비용이
  멀티스테이지의 builder 단계에 있으므로 기본값으로는 효과가 없다.
- `scope`를 api/web으로 나눴다. 한 캐시 공간을 공유하면 서로를 밀어낸다.

### 측정 결과

| 시나리오 | API | Web | 성격 |
|---|---|---|---|
| **캐시 없음(baseline)** | 98.5초 | 62.5초 | 실측(4회 중앙값) |
| **cold(캐시 채우는 첫 실행)** | 187초 | 109초 | 실측. baseline보다 느리다(+88 / +47초) |
| **완전 히트(같은 SHA 재실행)** | 5초 | 5초 | 실측. 상한값 |
| **소스만 변경** | 78초(중앙값, n=4 / 63~103) | 미측정 | §6 |
| **의존성 변경(`build.gradle.kts`)** | 113초 | 미측정 | §6 |

완전 히트(98.5 → 5초)는 같은 커밋을 다시 빌드해 `bootJar`까지 캐시된 경우다. 일상적인 케이스는
소스만 변경이며, 이때 78초(중앙값)다(§6).

## 6. 소스만 변경 실측 (2026-08-06, n=4)

측정을 위해 코드를 바꾸지 않았다. `apps/api/src`만 바뀐 머지가 실제로 발생한 뒤,
GitHub API로 `build & push` 스텝의 시작·종료 시각을 뽑아 §3과 같은 기준으로 계산했다.

| 커밋 | 바뀐 자바 파일 | `build & push` |
|---|---:|---:|
| `406b89f` (PR #171) | 2 | 63초 |
| `8da8bb3` (PR #168) | 4 (+마이그레이션 1) | 64초 |
| `df2eda1` | 5 | 92초 |
| `ebd71c9` (PR #169) | 10 | 103초 |
| `d56e2b7` (PR #170), 참고 | `build.gradle.kts` 포함 → 의존성 레이어 무효화 | 113초 |

```
소스만 변경 (n=4)   63 · 64 · 92 · 103    중앙값 78.0   평균 80.5
baseline   (n=9)   95 ~ 104               중앙값 99.0
```

### 결과

- 중앙값 99 → 78초, -21.2%.
- 4건 중 3건이 baseline의 최소값(95초)보다 빨랐다.

### 빌드별 편차

개별 빌드는 63~103초(폭 40초)로 분포해 개선 폭(21초)보다 넓다. `ebd71c9`는 103초로
baseline 중앙값보다 느렸다. baseline이 95~104초로 촘촘했던 것과 비교하면, 캐시 적용 후
중앙값은 줄고 분산은 커졌다.

§4의 분해로 계산하면 다음과 같다.
```
캐시에서 복원   base 18.0s + apt 6.2s + dependencies 38.8s ≈ 63.0s 분량
다시 실행       bootJar 29.6s
```
가장 빠른 63~64초는 이 계산(30초 + 복원 비용)과 대체로 맞는다. GHA 캐시에는 1,844MB가 올라가 있고
`mode=max`라 중간 레이어까지 내려받으므로, 캐시 다운로드 시간이 스텝 시간에 그대로 포함된다.

## 7. 비용

- cold 빌드가 느려진다. API +88초, Web +47초. cold는 `build.gradle.kts`/`pnpm-lock.yaml`
  변경, 7일 미사용 만료, 10GB 한도 초과 시 LRU 축출에서 다시 발생한다.
- GHA 캐시 1,844MB / 235개 항목을 쓴다(한도 10GB). `mode=max`가 중간 레이어까지 저장해
  항목 수가 많다. 콘텐츠 주소 기반이라 변하지 않은 레이어는 재사용되므로 빌드마다 선형으로
  늘지는 않는다. 한도에 근접하면 축출이 잦아져 cold가 반복될 수 있어 사용량을 모니터링 대상으로 둔다.
- 의존 액션이 2개 늘었다(`setup-buildx-action`, `build-push-action`). `setup-buildx` 자체가
  5~13초를 쓰며, 캐시 이득에서 차감해야 하는 비용이다.

## 8. 측정 조건

- GitHub Actions 러너 기준이다. baseline은 4회 측정의 중앙값, 소스만 변경은 n=4, cold/warm은 각 1회다.
- Web의 소스만 변경은 측정하지 않았다. §6의 머지는 모두 `apps/api`만 바뀌어
  Web은 완전 히트(5초)였다.
- OIDC 신뢰 조건을 `ref:refs/heads/main`으로 좁힌 상태라(PR #164) 이 워크플로는 브랜치에서
  시험할 수 없고, main에 머지한 뒤 측정했다. image.yml을 고칠 때마다 같은 제약이 적용된다
  (보안 강화와 검증 편의 사이의 트레이드오프).

## 9. 다음 개선

- **표본 보강**: `apps/api/src`만 바뀐 머지가 생길 때마다 §6 표에 한 줄씩 추가한다.
  n≥8에서 중앙값 -21%의 유지 여부와 103초 같은 느린 빌드의 빈도를 다시 본다.
- **편차 축소**: 손댈 곳은 캐시 크기다. `mode=max`는 중간 레이어까지 저장해 복원량을 키우므로,
  이득의 원천(의존성 레이어)만 남기고 줄이는 실험을 고려한다.
- **Web 별도 판단**: 캐시되는 `pnpm install`이 6.5초이고 재실행되는 `pnpm build`가 36.6초라
  이득이 API보다 작을 것으로 추정한다. 소스만 변경 머지가 생기면 측정해 따로 판단한다.
- **유지 여부**: cold +88.5초, 의존성 변경 +14.5초에서는 느려진다. 중앙값 이득 21초가 이를
  상쇄하는지는 머지 빈도에 달렸으므로, 머지 유형별 빈도를 세어 판단한다. 되돌리는 선택지도 열어 둔다.
