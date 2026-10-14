# TS-050 · 새 클러스터에서 helm 설치·ArgoCD 동기화가 모두 멈춤 — metrics-server 애드온(eksbuild.12)이 OpenAPI v2 protobuf를 503으로 만들었다

- 날짜: 2026-10-14 (A'/B 측정 세션 `20261014-ab` 기동 중)
- 영역: 배포 환경(EKS 애드온)
- 결과: **원인 확인 → 해결**(애드온 버전 고정)

## 증상
- `terraform apply`(84 added)는 성공. `bring-up.sh`가 3/7 aws-load-balancer-controller 설치에서 같은 오류로 두 번 멈췄다.
  ```
  Error: unable to build kubernetes objects from release manifest: error validating "": error validating data: the server is currently unable to handle the request
  ```
- helm client 검증을 끄고(`--disable-openapi-validation`, 세션 한정 래퍼) 넘기자 helm 릴리스 6개는 모두 `deployed`였지만, 7/7에서
  "Ingress가 ALB 주소를 받지 못했다"로 멈췄다. Ingress 자체가 없었다 — ArgoCD가 앱을 하나도 배포하지 않았다.
  ```
  ComparisonError: Failed to load live state: ... failed to load open api schema while syncing cluster cache:
  error getting openapi resources: the server is currently unable to handle the request
  ```

## 재현
새 클러스터(EKS 1.35, platform eks.25)에서 `bring-up.sh`를 두 번 돌려 두 번 모두 3/7에서 같은 오류. 아래 조사 3의 형식별 요청으로 언제든 재현됐다(1분간 6회 모두 503).

## 조사 (실측, 해석은 표시)
1. API 서버 자체는 정상: `readyz?verbose` 통과, `kubectl get apiservices` 전부 Available.
2. `kubectl apply --dry-run=server -v=6`로 실패 요청을 추적 → `GET /openapi/v2` **503**.
3. 같은 경로를 형식별로 비교(`kubectl proxy` + curl): `Accept: application/json` **200**, `Accept: application/com.github.proto-openapi.spec.v2@v1.0+protobuf` **503**(1분간 6회 모두).
   helm·kubectl·ArgoCD의 OpenAPI v2 조회는 protobuf를 먼저 요청한다(client-go discovery 동작에 근거한 추론 — `-v=6`은 Accept 헤더를 보여 주지 않는다. JSON이 200인데 이들이 실패했다는 것과는 맞는다).
4. LBC 차트를 문서별로 서버 dry-run → `kind: List`로 묶인 IngressClass 묶음 하나만 실패, 안의 두 리소스는 각각 통과.
   `List`는 kubectl이 펼치는 클라이언트 쪽 표현이라, 이 경로만 클라이언트가 OpenAPI v2(protobuf)를 요청한 것으로 본다(추론 — 개별 리소스는 서버 쪽 필드 검증으로 처리돼 클라이언트가 스펙을 받지 않는다).
5. JSON `/openapi/v2`에서 표준 밖 키를 훑었다 → `*_v2`로 끝나는 정의 14개(`Quantity_v2`, `ObjectMeta_v2`, `Info_v2` …)가
   `SchemaProps`·`SwaggerSchemaProps`·`ArbitraryKeys` 같은 **Go 구조체 필드 이름**으로 직렬화돼 있었고, 어디서도 참조되지 않았다.
   확장 API 서버(aggregated APIService)가 넘긴 스펙이 로컬 정의와 충돌해 이름이 바뀐 채 깨진 것으로 봤다(추론).
6. 확장 APIService 둘을 백업한 뒤 하나씩 빼 봤다.
   - `v1.metrics.eks.amazonaws.com`(EKS 관리) 삭제 → protobuf 503 그대로, `_v2` 14개 그대로 → 백업으로 복원.
   - `v1beta1.metrics.k8s.io`(metrics-server) 삭제 → protobuf **200**, `_v2` **0**. (이 상태에서 복원하지 않고 7로 넘어갔다 — 애드온 갱신이 APIService를 다시 만들었다.)
7. metrics-server 애드온을 `v0.9.0-eksbuild.12` → `v0.9.0-eksbuild.11`로 내림(`aws eks update-addon`) →
   aggregator 갱신 뒤 protobuf **200**(503 두 번 뒤), `_v2` **0**, `kubectl top nodes` 정상.
8. ArgoCD application-controller 재시작 + hard refresh → Synced/Healthy, Ingress ALB 할당.

## 근본 원인
- `aws_eks_addon`이 metrics-server 버전을 지정하지 않아 **생성 시점의 기본 호환 버전**이 설치된다. 이번 클러스터에는 `v0.9.0-eksbuild.12`(현재 기본값)가 깔렸다.
- 이 빌드의 APIService가 내보내는 OpenAPI v2가 API 서버의 protobuf 직렬화를 실패시켰다(위 6·7). 깨진 정의가 왜 생기는지(빌드 내부 변경)는 확인하지 못했다.
- 한계: eksbuild.12로 다시 올려 503이 재현되는지(역방향 대조)는 아직 하지 않았다. 그래서 "버전 때문"과 "APIService 재등록 효과"가 완전히 갈리지는 않는다. 7에서 eksbuild.11 APIService가 붙은 직후 503이 두 번 난 뒤 200이 된 것은 버전 쪽을 뒷받침한다. 역방향 대조는 측정 세션 끝(철거 전)에 한다.
- 2026-10-10 세션은 같은 코드로 문제가 없었다. 그 사이 기본 버전이 eksbuild.12로 바뀐 것으로 본다(추론 — 그 세션의 애드온 버전은 기록하지 않았다).

## 해결
- 세션: `aws eks update-addon --addon-name metrics-server --addon-version v0.9.0-eksbuild.11` (Terraform state와 다른 상태 — 철거로 함께 사라진다).
- 코드: `infra/terraform/platform/modules/eks/main.tf`에서 metrics-server만 `v0.9.0-eksbuild.11`로 고정. 나머지 애드온은 지금처럼 기본 버전.

## 재발 방지
- 버전을 고정했다. 올릴 때는 새 클러스터에서 아래가 200인지 먼저 본다.
  ```
  kubectl proxy --port=8099 &
  curl -s -o /dev/null -w '%{http_code}\n' -H 'Accept: application/com.github.proto-openapi.spec.v2@v1.0+protobuf' http://127.0.0.1:8099/openapi/v2
  kill %1
  ```
- 진단 순서(다음에 같은 오류를 보면): `kubectl -v=6`로 실패 경로 → JSON/protobuf 비교 → JSON 스펙의 `_v2`·비표준 키 → 확장 APIService를 백업 후 하나씩 제외.
- helm `--disable-openapi-validation` 우회는 helm만 넘길 뿐 ArgoCD는 넘기지 못한다 — 근본 원인을 고쳐야 한다.
- `bring-up.sh` 재실행 조건: helm 단계는 `upgrade --install`이라 재실행해도 같다. Route53은 `UPSERT`, 시딩은 upsert·멱등이지만
  KOPIS 동기화를 다시 호출하고 약 4분이 더 든다. 이번에는 3/7·7/7에서 멈춰 Route53·시딩이 실행되기 전이었다.

## 관련
- TS-019(metrics-server 없음 → EKS 애드온으로 고정), ADR-012(비용 통제 — terraform apply는 수동)
