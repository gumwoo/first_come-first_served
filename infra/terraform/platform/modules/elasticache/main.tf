# ElastiCache Redis: 대기열(ZSet)·토큰·SSE 팬아웃 pub/sub용.
# 정합성의 진실원은 DB다(ADR-003/006). Redis를 잃으면 대기열 상태는 잃지만 판매 정합성은 깨지지 않는다.
# 다중 AZ 여부와 근거는 var.multi_az 설명(ADR-012 §10).

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

  # 켜면 앱도 spring.data.redis.ssl.enabled=true여야 한다(아니면 연결 전부 실패, ADR-013).
  # auth_token(Redis AUTH)은 시크릿 관리가 따라와 범위에서 뺐다. 접근 제어는 SG가 맡는다.
  transit_encryption_enabled = var.transit_encryption
  at_rest_encryption_enabled = true

  # 데모는 매번 새로 만든다. 스냅샷을 남기면 저장 비용이 붙고 destroy도 느려진다.
  snapshot_retention_limit = 0

  apply_immediately = true

  tags = var.tags
}
