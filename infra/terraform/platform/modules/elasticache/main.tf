# ElastiCache Redis — 대기열(ZSet)·토큰·SSE 팬아웃 pub/sub용.
#
# 정합성의 진실원은 DB다(ADR-003/006). Redis는 대기열 순번·토큰·랭킹과
# Pod 간 팬아웃을 담당하므로, 유실 시 대기열 상태는 잃지만 판매 정합성은 깨지지 않는다.
# 그래서 데모 기본값은 단일 노드다 — 다만 아래 var.multi_az 주석의 논점을 참고할 것.

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "redis" {
  name        = "${var.name}-redis"
  description = "Redis from EKS nodes only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-redis" })
}

resource "aws_security_group_rule" "redis_from_nodes" {
  type                     = "ingress"
  security_group_id        = aws_security_group.redis.id
  source_security_group_id = var.allowed_security_group_id
  from_port                = 6379
  to_port                  = 6379
  protocol                 = "tcp"
  description              = "Redis from EKS cluster security group"
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name}-redis"
  description          = "FlowTicket queue/token/pubsub"

  engine         = "redis"
  engine_version = var.engine_version
  node_type      = var.node_type
  port           = 6379

  # 단일 노드면 1, 다중 AZ면 2 이상. num_cache_clusters는 프라이머리를 포함한 수다.
  num_cache_clusters         = var.multi_az ? 2 : 1
  multi_az_enabled           = var.multi_az
  automatic_failover_enabled = var.multi_az

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  # 전송 중 암호화를 켜면 클라이언트가 TLS로 접속해야 한다. 앱 설정(spring.data.redis.ssl)을
  # 함께 바꿔야 하므로 기본은 끔 — 프라이빗 서브넷 + SG로 접근을 제한하는 것으로 갈음한다.
  transit_encryption_enabled = false
  at_rest_encryption_enabled = true

  # 데모는 매번 새로 만든다. 스냅샷을 남기면 저장 비용이 붙고 destroy도 느려진다.
  snapshot_retention_limit = 0

  apply_immediately = true

  tags = var.tags
}
