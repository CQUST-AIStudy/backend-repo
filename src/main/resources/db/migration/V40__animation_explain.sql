-- =============================================
-- V40 迁移：学生端「动画讲解」模块
-- animation_explain：一次讲解任务（主题 -> 大纲 -> 多个 HTML 动画分镜）
-- animation_frame：单个分镜（HTML 动画 + 旁白音频）
-- =============================================

CREATE TABLE IF NOT EXISTS animation_explain (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  topic VARCHAR(512) NOT NULL,
  title VARCHAR(256) NULL,
  style VARCHAR(32) NOT NULL DEFAULT 'cyber-clean',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  progress INT NOT NULL DEFAULT 0,
  current_step VARCHAR(128) NULL,
  error_message TEXT NULL,
  frame_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动画讲解任务';

CREATE INDEX idx_anim_explain_user ON animation_explain(user_id);

CREATE TABLE IF NOT EXISTS animation_frame (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  explain_id BIGINT NOT NULL,
  frame_index INT NOT NULL,
  title VARCHAR(256) NULL,
  narration TEXT NULL,
  visual_hint TEXT NULL,
  html_object_key VARCHAR(512) NULL,
  audio_object_key VARCHAR(512) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_anim_frame_explain FOREIGN KEY (explain_id) REFERENCES animation_explain(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动画讲解分镜';

CREATE INDEX idx_anim_frame_explain ON animation_frame(explain_id);
