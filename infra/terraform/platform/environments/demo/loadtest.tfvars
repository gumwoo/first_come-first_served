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
# 기본값(AZ당 2 = 6노드)이면 HPA 상한까지 늘려도 예산 안에 들어가 CA가 발동하지 않는다.
node_max_size = 3
