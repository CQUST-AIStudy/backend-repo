package com.tap.backend.service.animation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTML 动画讲解的 3 套内置视觉风格（参考 ai-video-agent）。 */
public final class AnimationStylePresets {

    private AnimationStylePresets() {}

    public record StylePreset(String id, String name, String description, String prompt) {}

    private static final List<StylePreset> PRESETS = List.of(
            new StylePreset(
                    "cyber-clean",
                    "科技博主",
                    "深色科技感、青色霓虹、卡片化信息布局",
                    """
                    视觉风格：深色科技风（背景 #0a0e1a ~ #0f172a），青色霓虹点缀（#38bdf8），\
                    细网格背景，卡片化信息布局，无衬线字体，大标题 ≥ 48px，正文 ≥ 24px。\
                    画面中严禁显示旁白文字，旁白由独立字幕层处理；只展示可视化元素（数字、图表、图标、几何动画）。\
                    动画时长 4-8 秒，自动循环或结束时静止。"""
            ),
            new StylePreset(
                    "terminal-matrix",
                    "黑客风",
                    "终端界面、Matrix 绿、扫描线效果",
                    """
                    视觉风格：黑客终端风（黑底 #0a0a0a），Matrix 绿（#00ff41）为主色，\
                    等宽字体，扫描线/光标闪烁效果，ASCII 装饰，命令行风格布局。\
                    画面中严禁显示旁白文字；只展示代码片段、数据流、终端输出动画。\
                    动画时长 4-8 秒，自动循环。"""
            ),
            new StylePreset(
                    "warm-story",
                    "暖色系",
                    "奶油米色、暖橘琥珀、衬线标题和大留白",
                    """
                    视觉风格：暖色系人文风（奶油米色 #faf6f0 背景），暖橘琥珀点缀（#f59e0b），\
                    衬线标题字体，大留白，柔和圆角卡片，手绘感装饰元素。\
                    画面中严禁显示旁白文字；只展示插画元素、关键词标签、流程图示。\
                    动画时长 4-8 秒，自动循环。"""
            )
    );

    public static List<Map<String, Object>> listDto() {
        return PRESETS.stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.id());
                    m.put("name", p.name());
                    m.put("description", p.description());
                    return m;
                })
                .toList();
    }

    public static String resolvePrompt(String styleId) {
        if (styleId == null || styleId.isBlank()) {
            return PRESETS.get(0).prompt();
        }
        return PRESETS.stream()
                .filter(p -> p.id().equals(styleId))
                .findFirst()
                .orElse(PRESETS.get(0))
                .prompt();
    }
}
