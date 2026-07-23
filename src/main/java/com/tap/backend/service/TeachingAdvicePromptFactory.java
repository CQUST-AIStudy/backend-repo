package com.tap.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TeachingAdvicePromptFactory {
    public static final String VERSION = "teaching-advice-v1";

    private final ObjectMapper objectMapper;

    public TeachingAdvicePromptFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(String scopeLevel, Map<String, Object> context) {
        String focus = switch (scopeLevel) {
            case "EXPERIMENT" -> "比较同一实验在不同教学班的表现，指出差异最大的题目和本次实验后的调整动作";
            case "CLASS" -> "分析一个教学班历次实验趋势、薄弱环节和学生分层，给出下一阶段教学调整";
            case "COURSE" -> "汇总同一课程多个教学班及历史学期表现，给出课程安排和实验难度梯度调整";
            default -> throw new IllegalArgumentException("unsupported scope level: " + scopeLevel);
        };

        return """
                你是高校课程教学数据顾问。只能依据下方数据快照生成建议，不得使用未提供的信息。

                任务层级：%s
                任务重点：%s

                强制规则：
                1. 每个风险和行动必须引用数据快照中的 evidenceId。
                2. 数据不足时写入 limitations，不得补造结论。
                3. 建议必须具体到课堂、课后、实验安排或学生分层，并给出可验证的 successMetric。
                4. 不得声称访问过数据库、学生隐私数据或其他教师数据。
                5. 仅输出严格 JSON，不要输出 Markdown 或解释文字。

                输出结构：
                {
                  "summary":"结论摘要",
                  "risks":[{"level":"HIGH|MEDIUM|LOW","title":"风险","evidenceRefs":["M01"]}],
                  "actions":[{"priority":1,"action":"具体动作","target":"对象","evidenceRefs":["M01"],"successMetric":"验证指标"}],
                  "limitations":["数据限制"]
                }

                数据快照：
                %s
                """.formatted(scopeLevel, focus, toJson(context));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize teaching advice context", e);
        }
    }
}
