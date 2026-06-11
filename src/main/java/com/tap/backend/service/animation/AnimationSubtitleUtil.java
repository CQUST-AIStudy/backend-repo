package com.tap.backend.service.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 旁白字幕切片：按标点拆句，按字数占比分配时间轴。 */
public final class AnimationSubtitleUtil {

    private AnimationSubtitleUtil() {}

    public record SubtitleCue(String text, double startTime, double endTime) {}

    public static List<SubtitleCue> splitSubtitles(String narration, double audioDurationSec) {
        if (narration == null || narration.isBlank() || audioDurationSec <= 0) {
            return List.of();
        }
        String clean = narration.replaceAll("[。！？.!?]+\\s*$", "").trim();
        if (clean.isBlank()) {
            return List.of();
        }

        String[] rawSegments = clean.split("(?<=[，,。！？!?；;：:])|(?<=[，,；;：:])(?=\\S)");
        List<String> segments = new ArrayList<>();
        for (String segment : rawSegments) {
            String trimmed = segment.replaceAll("[,，。！？!?；;：:]+$", "").trim();
            if (!trimmed.isBlank()) {
                segments.add(trimmed);
            }
        }
        if (segments.isEmpty()) {
            return List.of();
        }
        if (segments.size() == 1) {
            return List.of(new SubtitleCue(segments.get(0), 0, audioDurationSec));
        }

        int totalChars = segments.stream().mapToInt(String::length).sum();
        if (totalChars == 0) {
            return List.of();
        }

        double acc = 0;
        List<SubtitleCue> cues = new ArrayList<>();
        for (String text : segments) {
            double ratio = (double) text.length() / totalChars;
            double start = acc * audioDurationSec;
            acc += ratio;
            double end = Math.min(acc, 1.0) * audioDurationSec;
            cues.add(new SubtitleCue(text, start, end));
        }
        return cues;
    }

    public static List<Map<String, Object>> toDto(List<SubtitleCue> cues) {
        return cues.stream()
                .map(cue -> Map.<String, Object>of(
                        "text", cue.text(),
                        "startTime", cue.startTime(),
                        "endTime", cue.endTime()
                ))
                .toList();
    }
}
