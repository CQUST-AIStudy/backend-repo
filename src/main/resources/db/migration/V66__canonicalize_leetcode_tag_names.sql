CREATE TEMPORARY TABLE leetcode_tag_name_mapping (
    legacy_name VARCHAR(64) COLLATE utf8mb4_0900_ai_ci PRIMARY KEY,
    canonical_name VARCHAR(64) COLLATE utf8mb4_0900_ai_ci NOT NULL
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO leetcode_tag_name_mapping (legacy_name, canonical_name) VALUES
('array', '数组'), ('linked_list', '链表'), ('stack', '栈'), ('queue', '队列'),
('tree', '树'), ('binary_tree', '树'), ('heap', '堆'), ('hash_table', '哈希表'),
('graph', '图'), ('string', '字符串'), ('sorting', '排序'), ('searching', '二分查找'),
('binary_search', '二分查找'), ('dfs', '深度优先搜索'), ('bfs', '广度优先搜索'),
('backtracking', '回溯'), ('greedy', '贪心'), ('divide_conquer', '分治'),
('graph_traversal', '图'), ('shortest_path', '最短路'), ('two_pointers', '双指针'),
('sliding_window', '滑动窗口'), ('dynamic_programming', '动态规划'),
('bit_manipulation', '位运算'), ('math', '数学'), ('simulation', '模拟'),
('prefix_sum', '前缀和'), ('monotonic_stack', '单调栈'), ('union_find', '并查集'),
('trie', '字典树');

INSERT INTO leetcode_problem_tag
    (problem_id, tag_name, tag_category, relevance_score, is_primary, created_at)
SELECT t.problem_id, m.canonical_name, t.tag_category, t.relevance_score, t.is_primary, t.created_at
FROM leetcode_problem_tag t
JOIN leetcode_tag_name_mapping m ON BINARY m.legacy_name = BINARY t.tag_name
ON DUPLICATE KEY UPDATE
    relevance_score = GREATEST(leetcode_problem_tag.relevance_score, VALUES(relevance_score)),
    is_primary = GREATEST(leetcode_problem_tag.is_primary, VALUES(is_primary));

DELETE t FROM leetcode_problem_tag t
JOIN leetcode_tag_name_mapping m ON BINARY m.legacy_name = BINARY t.tag_name;

INSERT INTO student_skill_state
    (student_id, tag_name, mastery_score, forgetting_score, confidence_score,
     attempt_count, success_count, avg_attempts_to_success, last_practice_at,
     updated_at, created_at)
SELECT s.student_id, m.canonical_name, MIN(s.mastery_score), MAX(s.forgetting_score),
       MAX(s.confidence_score), MAX(s.attempt_count), MAX(s.success_count),
       MIN(s.avg_attempts_to_success), MAX(s.last_practice_at),
       MAX(s.updated_at), MIN(s.created_at)
FROM student_skill_state s
JOIN leetcode_tag_name_mapping m ON BINARY m.legacy_name = BINARY s.tag_name
GROUP BY s.student_id, m.canonical_name
ON DUPLICATE KEY UPDATE
    mastery_score = LEAST(student_skill_state.mastery_score, VALUES(mastery_score)),
    forgetting_score = GREATEST(student_skill_state.forgetting_score, VALUES(forgetting_score)),
    confidence_score = GREATEST(student_skill_state.confidence_score, VALUES(confidence_score)),
    attempt_count = GREATEST(student_skill_state.attempt_count, VALUES(attempt_count)),
    success_count = GREATEST(student_skill_state.success_count, VALUES(success_count)),
    last_practice_at = CASE
        WHEN student_skill_state.last_practice_at IS NULL THEN VALUES(last_practice_at)
        WHEN VALUES(last_practice_at) IS NULL THEN student_skill_state.last_practice_at
        ELSE GREATEST(student_skill_state.last_practice_at, VALUES(last_practice_at))
    END,
    updated_at = GREATEST(student_skill_state.updated_at, VALUES(updated_at));

DELETE s FROM student_skill_state s
JOIN leetcode_tag_name_mapping m ON BINARY m.legacy_name = BINARY s.tag_name;

DROP TEMPORARY TABLE leetcode_tag_name_mapping;
