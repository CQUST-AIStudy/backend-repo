package com.tap.backend.service.grading.animation;

/**
 * 错误演示动画的工作流类型。
 */
public enum AnimationWorkflow {
    /**
     * 代码高亮 + 点击弹窗动画：适合代码类错误（数组越界、指针、死循环等）。
     * 由大模型生成 errorRanges 与 D3 弹窗 HTML。
     */
    CODE_HIGHLIGHT,

    /**
     * Python Tutor 式执行可视化：保留作为轻量 fallback。
     */
    PYTHON_TUTOR,

    /**
     * AI 生成 HTML 动画：适合概念/原理类错误。
     * @deprecated 由 {@link #CONCEPT_STEPS} 取代——不再让大模型直出整段 HTML，
     * 改为让其产出结构化步骤 JSON，交由固定引擎渲染，保证质量一致。保留枚举仅作兼容。
     */
    @Deprecated
    HTML_ANIMATION,

    /**
     * 概念/原理类错误的分步可视化：大模型只产出「步骤数据」（dataStructure + 每步的
     * 代码行高亮 / 旁白字幕 / 节点边状态 / 变量），由前端固定的 PythonTutorRenderer 渲染。
     * 三条轨道（代码行 ↔ 画面 ↔ 字幕）同步，效果对齐图码式教学动画。
     */
    CONCEPT_STEPS,

    /**
     * 图文对比 + 简单动画：适合结果/数据类错误。
     */
    RESULT_COMPARE,

    /**
     * 通用高亮：无法归类时的兜底方案。
     */
    GENERIC_HIGHLIGHT
}
