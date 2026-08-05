# RDS PostgreSQL — 프라이빗 데이터 서브넷, 외부 노출 없음.
#
# 비밀번호는 Terraform이 생성해 Secrets Manager에 넣고, 밖으로는 ARN만 내보낸다.
# 프로젝트 규칙상 비밀은 코드·설정·채팅에 노출하지 않는다 — state 파일도 평문이므로
# output으로 값을 흘리지 않는 것이 중요하다.

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

resource "random_password" "master" {
  length = 32
  # RDS가 허용하지 않는 문자(/ @ " 공백)를 피한다.
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret" "master" {
  name        = "${var.name}/rds/master"
  description = "FlowTicket RDS master credentials"
  tags        = var.tags

  # 데모는 apply/destroy를 반복한다. 기본 복구 대기(7~30일)가 걸리면 같은 이름으로
  # 다시 만들 수 없어 다음 apply가 막힌다.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "master" {
  secret_id = aws_secretsmanager_secret.master.id
  secret_string = jsonencode({
    username = var.username
    password = random_password.master.result
    dbname   = var.db_name
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
  })
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
  password = random_password.master.result

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
