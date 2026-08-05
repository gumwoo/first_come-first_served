# AWS Load Balancer Controller IAM 정책

이 디렉터리에 **`aws-load-balancer-controller.json`** 이 있어야 `terraform plan`이 통과한다.

## 왜 저장소에 커밋되어 있지 않나

이 컨트롤러에는 AWS 관리형 정책이 없고, **공식 저장소가 배포하는 JSON을 그대로 쓰는 것이
표준 절차**다. 손으로 옮겨 적으면 누락·오타가 생겨도 `apply`는 성공하고, 나중에 Ingress가
만들어지지 않는 이유를 런타임에 추적하게 된다. 그래서 **원본을 받아서 쓴다**.

## 받는 법

`aws-load-balancer-controller` 공식 릴리스에 포함된 `iam_policy.json`을 받아
이 경로에 `aws-load-balancer-controller.json` 이름으로 저장한다.

```bash
curl -o infra/terraform/platform/modules/eks/policies/aws-load-balancer-controller.json \
  https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json
```

> 위 경로는 상류 저장소 구조에 따라 바뀔 수 있다. 404가 나면 설치하려는 **컨트롤러 버전의
> 릴리스 문서**에서 `iam_policy.json` 위치를 확인해 받는다. 받은 뒤 파일이 실제 IAM 정책
> 문서(`"Version"`, `"Statement"` 키를 가진 JSON)인지 눈으로 확인한다.

## 커밋할까

**커밋하는 편을 권한다.** 정책 내용이 바뀌면 diff로 드러나고, 다른 환경에서 `plan`을 돌릴 때
같은 절차를 반복하지 않아도 된다. 비밀이 아니라 공개 문서다.
