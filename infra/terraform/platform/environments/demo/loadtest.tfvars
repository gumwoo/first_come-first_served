# 부하·오토스케일 측정용 프로파일(ADR-012 §5 "인스턴스 계열을 목적별로 전환한다"). 기본값은 기능·배포·페일오버 검증용이다.
#   terraform -chdir=infra/terraform/platform/environments/demo apply -var-file=loadtest.tfvars
# ⚠️ 기본값보다 비싸다. 철거는 사용자 승인 후(resource-sizing-plan §5.1).

# t3는 버스터블이라 지속 부하에서 CPU 크레딧이 결과에 개입한다(TS-034, ADR-012 §5).
# m6i.large는 t3.large와 같은 2 vCPU / 8 GiB라 성격만 바뀌어 이전 측정과 비교할 수 있다.
node_instance_type = "m6i.large"

# AZ당 3 → 최대 9노드 = 18 vCPU(쿼터 32 이내). 기본값(6노드)이면 api HPA 상한 9(ADR-025, api 하나가 노드 하나)에
# 노드가 모자라 api가 Pending으로 남는다(계산).
node_max_size = 3

# 클러스터 밖 부하 발생기. 상한은 EC2 Standard vCPU 쿼터 계산값이다(loadtest-100k-plan §4 "발생기 구성 상한"):
#   쿼터 32 − 현재 사용 0 − EKS 최대 18 = 14 vCPU → c6i.xlarge(4 vCPU) 최대 3대. 이 상한을 넘겨 늘리지 않는다.
loadgen_instance_type  = "c6i.xlarge"
loadgen_instance_count = 3

# Redis를 비버스터블로(TS-049). T 노드는 크레딧이 바닥나면 기준 성능에 묶이고, 새 클러스터는 크레딧이 거의 0에서
# 시작해 측정이 무효가 된다. 비용 근거는 IMP-040. ElastiCache는 EC2 vCPU 쿼터와 별개다.
redis_node_type = "cache.m7g.large"
