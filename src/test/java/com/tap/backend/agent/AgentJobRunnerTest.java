package com.tap.backend.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import com.tap.backend.ai.AiProvider.FileClassifySummary;
import com.tap.backend.ai.AiProvider.FolderOrganizeResult;
import com.tap.backend.ai.AiProvider.PlacementRule;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentJobRunnerTest {

  @Test
  void buildLocalFallbackOrganizeResultUsesDominantTopicAndStableSchema() {
    List<FileClassifySummary> summaries = List.of(
        new FileClassifySummary(1L, "lecture1.pdf", "teaching", "数据结构", List.of("课程", "教案"), List.of(), "s1", "2025", 0.92),
        new FileClassifySummary(2L, "lecture2.pdf", "teaching", "数据结构", List.of("课程"), List.of(), "s2", "2025", 0.88),
        new FileClassifySummary(3L, "lab.zip", "code", "算法分析", List.of("实验"), List.of(), "s3", "2025", 0.77)
    );

    FolderOrganizeResult result = AgentJobRunner.buildLocalFallbackOrganizeResult(summaries);

    assertEquals("数据结构", result.folderTopic());
    assertIterableEquals(List.of("课程", "教案", "实验"), result.folderTags());
    assertEquals("docKind", result.groupingStrategy());
    assertIterableEquals(List.of("论文", "教学资料", "代码与实验", "数据", "行政材料", "其他", "待确认"), result.folderSchema());
    assertIterableEquals(List.of(
        new PlacementRule("docKind==paper", "论文"),
        new PlacementRule("docKind==teaching", "教学资料"),
        new PlacementRule("docKind==code", "代码与实验"),
        new PlacementRule("docKind==data", "数据"),
        new PlacementRule("docKind==admin", "行政材料"),
        new PlacementRule("docKind==other", "其他")
    ), result.placementRules());
    assertEquals("{topic}_{filename}", result.namingRule());
    assertEquals(0.5, result.reviewThreshold());
  }

  @Test
  void normalizeFolderOrganizeResultFillsMissingFieldsFromFallback() {
    List<FileClassifySummary> summaries = List.of(
        new FileClassifySummary(1L, "report.pdf", "paper", "机器学习", List.of("论文"), List.of(), "s1", "2024", 0.66)
    );

    FolderOrganizeResult normalized = AgentJobRunner.normalizeFolderOrganizeResult(
        new FolderOrganizeResult(
            "  ",
            List.of("  ", "专题"),
            "",
            List.of("  "),
            Arrays.asList(new PlacementRule(" ", " "), null),
            " ",
            3.0
        ),
        summaries
    );

    assertEquals("机器学习", normalized.folderTopic());
    assertIterableEquals(List.of("专题"), normalized.folderTags());
    assertEquals("docKind", normalized.groupingStrategy());
    assertIterableEquals(List.of("论文", "教学资料", "代码与实验", "数据", "行政材料", "其他", "待确认"), normalized.folderSchema());
    assertIterableEquals(List.of(
        new PlacementRule("docKind==paper", "论文"),
        new PlacementRule("docKind==teaching", "教学资料"),
        new PlacementRule("docKind==code", "代码与实验"),
        new PlacementRule("docKind==data", "数据"),
        new PlacementRule("docKind==admin", "行政材料"),
        new PlacementRule("docKind==other", "其他")
    ), normalized.placementRules());
    assertEquals("{topic}_{filename}", normalized.namingRule());
    assertEquals(0.5, normalized.reviewThreshold());
  }
}
