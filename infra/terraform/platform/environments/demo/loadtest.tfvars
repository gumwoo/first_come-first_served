# 부하·오토스케일 측정용 프로파일. ADR-012 §5의 "인스턴스 계열을 목적별로 전환한다"를
# 코드로 고정한 것이다. 기본값(t3.large × 최대 6)은 기능·배포·페일오버 검증용으로 그대로 둔다.
#
# 사용:
#   terraform -chdir=infra/terraform/platform/environments/demo apply -var-file=loadtest.tfvars
#
# ⚠️ 이 프로파일은 기본값보다 비싸다. 측정이 끝나면 반드시 destroy 한다(scripts/tear-down.sh).

# t3는 버스터블이라 지속 부하에서 CPU 크레딧이 결과에 개입한다. TS-034가 knee 재측정에
# 실패한 뒤 남긴 조건이고, ADR-012 §5가 이미 "부하테스트는 비버스터블"이라고 적어 두었다.
# m6i.large는 t3.large와 같은 2 vCPU / 8 GiB라 **자원량이 아니라 성격만 바꾼다** —
# 그래야 이전 측정과 비교할 수 있다.
node_instance_type = "m6i.large"

# AZ당 3 → 최대 9노드 = 18 vCPU(쿼터 32 이내).
# Cluster Autoscaler가 노드를 실제로 붙이는 구간을 보려면 상한에 여유가 있어야 한다.
# 기본값(AZ당 2 = 6노드)이면 api HPA 상한 9(ADR-025 — api 하나가 노드 하나)에 노드가 모자라 api가 Pending으로 남는다(계산).
node_max_size = 3

# 클러스터 밖 부하 발생기(loadtest-100k-plan §4 "발생기 구성 상한"). 상한은 EC2 Standard vCPU 쿼터에서 나온 계산값이다:
#   쿼터 32 − 현재 사용 0 − EKS 최대 18 = 14 vCPU → c6i.xlarge(4 vCPU) 최대 3대 = 12 vCPU, 여유 2.
# 측정 세션의 단계 2에서 실제 발생 용량을 확인한다. 이 상한을 넘겨 늘리지 않는다.
loadgen_instance_type  = "c6i.xlarge"
loadgen_instance_count = 3

# Redis를 비버스터블로(TS-049). t4g.micro는 크레딧이 바닥나면 기준 성능(약 10%)으로 묶여 상태 조회가 수백 ms로 느려졌다.
# 새 클러스터는 크레딧이 거의 0에서 시작하고 폭증 run 1회가 13 이상을 써서, 기다리지 않으면 run이 무효가 된다.
# m7g.large(2 vCPU · 6.38 GiB)는 크레딧이 없다. 서울 온디맨드 Redis $0.192/h × 2노드(primary+replica) —
# t4g.micro($0.024/h)보다 세션당 약 +$1~2(4~6시간, 계산·2026-10-13 요금 API 조회). ElastiCache는 EC2 vCPU 쿼터와 별개다.
redis_node_type = "cache.m7g.large"
