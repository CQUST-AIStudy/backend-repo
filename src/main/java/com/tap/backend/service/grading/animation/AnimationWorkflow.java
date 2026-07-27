package com.tap.backend.service.grading.animation;

/**
 * 错误演示动画的工作流类型。
 * <p>
 * 统一为「步骤 schema → 前端固定渲染器」两条生产线（对齐图码 totuma.cn 的教学动画形态）：
 * 每个演示都是若干步骤帧，每帧含代码行高亮、旁白字幕、数据结构快照、变量状态，
 * 由前端 {@code PythonTutorRenderer} + 步骤播放器统一呈现。
 * 旧的 CODE_HIGHLIGHT（LLM 直出 D3 弹窗 HTML）、HTML_ANIMATION（LLM 直出整页 HTML）、
 * RESULT_COMPARE、GENERIC_HIGHLIGHT 已全部移除。
 */
public enum AnimationWorkflow {
    /**
     * 真实执行可视化：把学生 C 代码放进沙箱执行，用 trace 生成逐行步骤帧。
     * 失败时由分发服务回退到 {@link #CONCEPT_STEPS}。
     */
    PYTHON_TUTOR,

    /**
     * 概念/原理类错误的分步可视化：大模型只产出「步骤数据」（dataStructure + 每步的
     * 代码行高亮 / 旁白字幕 / 节点边状态 / 变量），由前端固定的 PythonTutorRenderer 渲染。
     * 三条轨道（代码行 ↔ 画面 ↔ 字幕）同步，效果对齐图码式教学动画。
     */
    CONCEPT_STEPS
}
