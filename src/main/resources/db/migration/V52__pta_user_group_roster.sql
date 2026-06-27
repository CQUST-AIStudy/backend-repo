-- V51 PTA用户组花名册
-- 作用：把PTA用户组成员作为班级正式成员来源，避免某次考试的额外参与者污染班级人数。

SET @db_name := DATABASE();

CREATE TABLE IF NOT EXISTS pta_user_group (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  class_id BIGINT NULL COMMENT '关联的教学班级ID；为空表示暂未绑定到系统班级',
  pta_group_id VARCHAR(64) NOT NULL COMMENT 'PTA用户组ID，来自PTA userGroupId',
  pta_group_name VARCHAR(256) NULL COMMENT 'PTA用户组名称',
  source_system VARCHAR(32) NOT NULL DEFAULT 'PTA' COMMENT '数据来源系统，固定为PTA',
  member_count INT NOT NULL DEFAULT 0 COMMENT '本次同步时PTA用户组成员数量',
  last_roster_sync_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT '最近一次同步用户组花名册的时间',
  raw_json JSON NULL COMMENT 'PTA用户组原始元数据，便于排查接口变化',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  CONSTRAINT uq_pta_user_group_group UNIQUE (pta_group_id),
  CONSTRAINT fk_pta_user_group_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PTA用户组表：记录PTA用户组与系统教学班级的绑定关系，作为班级花名册同步入口';

CREATE TABLE IF NOT EXISTS pta_user_group_member (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
  pta_user_group_id BIGINT NOT NULL COMMENT '关联pta_user_group.id',
  class_id BIGINT NULL COMMENT '冗余保存教学班级ID，便于按班级查询花名册',
  student_id BIGINT NULL COMMENT '关联student_profile.id；同步时按学号匹配或创建',
  student_no VARCHAR(32) NOT NULL COMMENT '学生学号，来自PTA studentUser.studentNumber',
  student_name VARCHAR(128) NOT NULL COMMENT '学生姓名，来自PTA studentUser.name',
  pta_member_id VARCHAR(64) NULL COMMENT 'PTA用户组成员关系ID，来自members.id',
  pta_user_id VARCHAR(64) NULL COMMENT 'PTA登录用户ID，来自members.userId',
  pta_student_user_id VARCHAR(64) NULL COMMENT 'PTA学生档案ID，来自members.studentUserId',
  member_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '成员状态：ACTIVE当前在组内，LEFT曾在组内但本次同步未出现',
  joined_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次同步为该用户组成员的时间',
  left_at TIMESTAMP(3) NULL DEFAULT NULL COMMENT '从PTA用户组移除时记录的时间',
  raw_json JSON NULL COMMENT 'PTA成员原始JSON，便于排查字段变化',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  CONSTRAINT uq_pta_user_group_member_student UNIQUE (pta_user_group_id, student_no),
  CONSTRAINT fk_pta_user_group_member_group FOREIGN KEY (pta_user_group_id) REFERENCES pta_user_group(id) ON DELETE CASCADE,
  CONSTRAINT fk_pta_user_group_member_class FOREIGN KEY (class_id) REFERENCES teaching_class(id) ON DELETE SET NULL,
  CONSTRAINT fk_pta_user_group_member_student FOREIGN KEY (student_id) REFERENCES student_profile(id) ON DELETE SET NULL,
  CONSTRAINT chk_pta_user_group_member_status CHECK (member_status IN ('ACTIVE', 'LEFT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PTA用户组成员表：保存PTA用户组的权威学生花名册，考试额外参与者不写入本表';

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'pta_user_group'
    AND index_name = 'idx_pta_user_group_class'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_pta_user_group_class ON pta_user_group(class_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'pta_user_group_member'
    AND index_name = 'idx_pta_user_group_member_class'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_pta_user_group_member_class ON pta_user_group_member(class_id, member_status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'pta_user_group_member'
    AND index_name = 'idx_pta_user_group_member_student'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_pta_user_group_member_student ON pta_user_group_member(student_id, member_status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'pta_user_group_member'
    AND index_name = 'idx_pta_user_group_member_pta_user'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_pta_user_group_member_pta_user ON pta_user_group_member(pta_user_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- assignment_offering记录本次作业/实验来自哪个PTA用户组，后续生成学生作业时优先使用该用户组花名册。
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = @db_name
    AND table_name = 'assignment_offering'
    AND column_name = 'pta_user_group_id'
);
SET @sql := IF(@col = 0, 'ALTER TABLE assignment_offering ADD COLUMN pta_user_group_id BIGINT NULL COMMENT ''PTA用户组表ID；用于按用户组花名册生成学生作业'' AFTER pta_problem_set_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = @db_name
    AND table_name = 'assignment_offering'
    AND column_name = 'pta_group_id'
);
SET @sql := IF(@col = 0, 'ALTER TABLE assignment_offering ADD COLUMN pta_group_id VARCHAR(64) NULL COMMENT ''PTA用户组ID快照；便于不联表排查同步来源'' AFTER pta_user_group_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE table_schema = @db_name
    AND table_name = 'assignment_offering'
    AND constraint_name = 'fk_assignment_offering_pta_user_group'
);
SET @sql := IF(@fk = 0, 'ALTER TABLE assignment_offering ADD CONSTRAINT fk_assignment_offering_pta_user_group FOREIGN KEY (pta_user_group_id) REFERENCES pta_user_group(id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'assignment_offering'
    AND index_name = 'idx_assignment_offering_pta_user_group'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_assignment_offering_pta_user_group ON assignment_offering(pta_user_group_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'assignment_offering'
    AND index_name = 'idx_assignment_offering_pta_group_id'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_assignment_offering_pta_group_id ON assignment_offering(pta_group_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- student_assignment区分正式花名册学生和只参加某次考试/实验的外部参与者。
SET @col := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = @db_name
    AND table_name = 'student_assignment'
    AND column_name = 'roster_scope'
);
SET @sql := IF(@col = 0, 'ALTER TABLE student_assignment ADD COLUMN roster_scope VARCHAR(32) NOT NULL DEFAULT ''CLASS_ROSTER'' COMMENT ''学生作业来源：CLASS_ROSTER班级花名册，PTA_USER_GROUP用户组花名册，GUEST_PARTICIPANT本次实验额外参与者'' AFTER student_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = @db_name
    AND table_name = 'student_assignment'
    AND index_name = 'idx_student_assignment_roster_scope'
);
SET @sql := IF(@idx = 0, 'CREATE INDEX idx_student_assignment_roster_scope ON student_assignment(offering_id, roster_scope)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
