-- ========== 技能标签种子数据 ==========
-- 创建时间: 2026-03-12
-- 说明: 预定义的算法技能标签白名单

-- 创建临时表存储标签定义
CREATE TEMPORARY TABLE temp_skill_tags (
    tag_name VARCHAR(64) NOT NULL,
    tag_category ENUM('algorithm','data_structure','technique') NOT NULL,
    description VARCHAR(255) NOT NULL
);

-- 插入数据结构标签
INSERT INTO temp_skill_tags (tag_name, tag_category, description) VALUES
('array', 'data_structure', '数组操作与遍历'),
('linked_list', 'data_structure', '链表操作'),
('stack', 'data_structure', '栈数据结构'),
('queue', 'data_structure', '队列数据结构'),
('tree', 'data_structure', '树结构'),
('binary_tree', 'data_structure', '二叉树'),
('heap', 'data_structure', '堆数据结构'),
('hash_table', 'data_structure', '哈希表'),
('graph', 'data_structure', '图结构'),
('string', 'data_structure', '字符串处理');

-- 插入算法标签
INSERT INTO temp_skill_tags (tag_name, tag_category, description) VALUES
('sorting', 'algorithm', '排序算法'),
('searching', 'algorithm', '搜索算法'),
('binary_search', 'algorithm', '二分搜索'),
('dfs', 'algorithm', '深度优先搜索'),
('bfs', 'algorithm', '广度优先搜索'),
('backtracking', 'algorithm', '回溯算法'),
('greedy', 'algorithm', '贪心算法'),
('divide_conquer', 'algorithm', '分治算法'),
('graph_traversal', 'algorithm', '图遍历'),
('shortest_path', 'algorithm', '最短路径算法');

-- 插入技巧标签
INSERT INTO temp_skill_tags (tag_name, tag_category, description) VALUES
('two_pointers', 'technique', '双指针技巧'),
('sliding_window', 'technique', '滑动窗口'),
('dynamic_programming', 'technique', '动态规划'),
('bit_manipulation', 'technique', '位运算'),
('math', 'technique', '数学计算'),
('simulation', 'technique', '模拟'),
('prefix_sum', 'technique', '前缀和'),
('monotonic_stack', 'technique', '单调栈'),
('union_find', 'technique', '并查集'),
('trie', 'technique', '字典树');

-- 注意：这里只是创建了标签定义，实际的标签会在题目同步时根据内容匹配自动创建
-- 这个脚本主要用于文档化标签体系，实际使用时标签会动态生成

DROP TEMPORARY TABLE temp_skill_tags;