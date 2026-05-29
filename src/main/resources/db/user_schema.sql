-- 创建 user 表，存储用户信息
CREATE TABLE IF NOT EXISTS `user` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `email` VARCHAR(100) COMMENT '邮箱',
    `role` VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '角色: student/teacher/admin',
    `usernum` VARCHAR(20) COMMENT '学号或工号',
    `classname` VARCHAR(50) COMMENT '班级名称',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
);

-- 插入测试用户数据
INSERT INTO `user` (`username`, `password`, `email`, `role`, `usernum`, `classname`) VALUES
('student1', '$2a$10$RiIfr7fwVhHVxLwmwK5dnOtDWiEdXtaGatoGeRf9qWjVHckXpNPJe', 'student1@example.com', 'student', '2023001', '计算机科学1班'),
('student2', '$2a$10$RiIfr7fwVhHVxLwmwK5dnOtDWiEdXtaGatoGeRf9qWjVHckXpNPJe', 'student2@example.com', 'student', '2023002', '计算机科学1班'),
('teacher1', '$2a$10$RiIfr7fwVhHVxLwmwK5dnOtDWiEdXtaGatoGeRf9qWjVHckXpNPJe', 'teacher1@example.com', 'teacher', 'T001', NULL),
('admin1', '$2a$10$RiIfr7fwVhHVxLwmwK5dnOtDWiEdXtaGatoGeRf9qWjVHckXpNPJe', 'admin1@example.com', 'admin', 'A001', NULL);