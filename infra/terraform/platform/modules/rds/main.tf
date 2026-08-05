# RDS PostgreSQL — 프라이빗 데이터 서브넷, 외부 노출 없음.
#
# 비밀번호는 **RDS가 직접 관리**한다(manage_master_user_password).
# 처음에는 Terraform이 random_password로 만들어 Secrets Manager에 넣었는데,
# 그 방식은 생성한 값이 **state에 평문으로 남는다** — output으로 안 내보내도 마찬가지다.
# RDS가 관리하면 Terraform은 비밀번호를 보지도 저장하지도 않고 시크릿 ARN만 참조한다.
# (프로젝트 규칙: 비밀은 코드·설정·state·채팅 어디에도 남기지 않는다.)

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "PostgreSQL from EKS nodes only"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-db" })
}

# CIDR이 아니라 클러스터 SG를 출발지로 지정한다 — "이 클러스터의 노드만"이라는
# 의도가 규칙 자체에 드러나고, 서브넷 CIDR이 바뀌어도 따라 고칠 필요가 없다.
resource "aws_security_group_rule" "db_from_nodes" {
  type                     = "ingress"
  security_group_id        = aws_security_group.db.id
  source_security_group_id = var.allowed_security_group_id
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  description              = "PostgreSQL from EKS cluster security group"
}

resource "aws_db_instance" "this" {
  identifier     = var.name
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.username

  # 비밀번호를 Terraform이 만들지 않는다 — RDS가 생성·저장·교체하고
  # Secrets Manager 시크릿을 알아서 만든다. ARN은 master_user_secret으로 노출된다.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  multi_az = var.multi_az

  # 데모용 — 자동 백업을 끄고 최종 스냅샷도 남기지 않는다.
  # 데이터는 Flyway 마이그레이션·KOPIS 동기화·좌석 시딩으로 기동 시 다시 채워진다.
  backup_retention_period = var.backup_retention_period
  skip_final_snapshot     = true
  deletion_protection     = false

  # 마이너 버전 자동 업그레이드가 재기동을 유발하면 실증 중 방해가 된다.
  auto_minor_version_upgrade = false
  apply_immediately          = true

  tags = var.tags
}
