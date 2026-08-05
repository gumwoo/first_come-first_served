# AWS Load Balancer Controller IAM 정책

**`aws-load-balancer-controller.json`은 이 저장소에 커밋되어 있다.** 추가로 받을 것은 없다.

## 왜 손으로 쓰지 않고 원본을 가져오나

이 컨트롤러에는 AWS 관리형 정책이 없고, **공식 저장소가 배포하는 JSON을 그대로 쓰는 것이
표준 절차**다. 손으로 옮겨 적으면 권한 하나가 빠져도 `apply`는 성공하고, 나중에 Ingress가
만들어지지 않는 이유를 런타임에 추적하게 된다.

## 출처

```
kubernetes-sigs/aws-load-balancer-controller
  docs/install/iam_policy.json
```

받은 시점: 2026-08-05. 내용 확인 결과 statement 16개, 전부 `Effect: Allow`,
`elasticloadbalancing` · `ec2` · `acm` · `iam` · `wafv2` · `waf-regional` · `shield` · `cognito-idp`
네임스페이스를 사용한다.

## 커밋해 두는 이유

- 정책이 바뀌면 **diff로 드러난다** — 조용히 권한이 늘거나 줄지 않는다.
- `terraform validate`가 `file()`을 정적으로 평가하므로, 파일이 없으면 **CI 검증 자체가 실패**한다.
- 비밀이 아니라 공개 문서다.

## 갱신

컨트롤러를 새 버전으로 올릴 때는 **그 버전의 릴리스 문서에 있는 `iam_policy.json`**으로 교체하고,
diff를 확인한 뒤 커밋한다. 버전이 다르면 필요한 권한도 달라진다.

```bash
curl -o infra/terraform/platform/modules/eks/policies/aws-load-balancer-controller.json \
  https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json
```
