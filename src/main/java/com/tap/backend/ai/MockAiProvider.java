package com.tap.backend.ai;

import java.util.*;

public class MockAiProvider implements AiProvider {
  @Override public String name() { return "mock"; }

  @Override
  public FileClassifyResult classifyFile(FileClassifyInput input) {
    String text = input.text() == null ? "" : input.text().toLowerCase(Locale.ROOT);
    String docKind = "other";
    if (text.contains("abstract") || text.contains("论文") || text.contains("references")) docKind = "paper";
    else if (text.contains("课程") || text.contains("讲义") || text.contains("教学")) docKind = "teaching";
    else if (text.contains("实验") || text.contains("代码")) docKind = "code";

    Set<String> tags = new LinkedHashSet<>();
    if (text.contains("transformer") || text.contains("attention")) tags.add("NLP");
    if (text.contains("graph")) tags.add("Graph");
    if (tags.isEmpty()) tags.add("General");

    String summary = text.length() > 200 ? text.substring(0, 200) : "该文档为课程资料，包含概念讲解与要点。";
    return new FileClassifyResult(docKind, "数据结构", new ArrayList<>(tags),
        List.of("课程", "讲义", "重点"), summary, null, 0.7, "mock分类：基于关键词匹配");
  }

  @Override
  public FolderOrganizeResult organizeFolder(FolderOrganizeInput input) {
    Set<String> tags = new LinkedHashSet<>();
    for (var d : input.documents()) {
      if (d.subjectTags() != null) tags.addAll(d.subjectTags());
    }
    if (tags.isEmpty()) tags.add("General");

    return new FolderOrganizeResult(
        "课程资料整理（mock）",
        new ArrayList<>(tags),
        "docKind > topic",
        List.of("论文", "教学资料", "代码与实验", "其他", "待确认"),
        List.of(
            new PlacementRule("docKind==paper", "论文"),
            new PlacementRule("docKind==teaching", "教学资料"),
            new PlacementRule("docKind==code", "代码与实验"),
            new PlacementRule("docKind==other", "其他")
        ),
        "{topic}_{filename}",
        0.5
    );
  }

  @Override
  public StructuredSummary structuredSummary(StructuredSummaryInput input) {
    return new StructuredSummary(
        "本文围绕一个明确的教学/研究问题展开。",
        List.of("提出核心假设", "构建关键模块", "给出设计取舍"),
        List.of("在公开数据上对比", "报告核心指标"),
        "结果表明所提出的方法取得稳定提升。",
        List.of("对数据分布变化仍可能敏感", "启发：可将方法拆成课堂小实验")
    );
  }

  @Override
  public String chat(String prompt, String modelOverride) {
    return "{\"summary\":\"\\u8fd9\\u662f\\u6a21\\u62df\\u7684\\u6279\\u6b21\\u603b\\u8bc4\\uff0c\\u5b9e\\u9645\\u90e8\\u7f72\\u65f6\\u8bf7\\u5207\\u6362\\u5230\\u771f\\u5b9eAI\\u63d0\\u4f9b\\u5546\\u3002\","
        + "\"commonIssues\":[{\"issue\":\"\\u6a21\\u62df\\u5171\\u6027\\u95ee\\u9898\",\"affectedRatio\":\"\\u7ea630%\",\"suggestion\":\"\\u6a21\\u62df\\u6559\\u5b66\\u6539\\u8fdb\\u5efa\\u8bae\"}],"
        + "\"strengths\":[\"\\u6a21\\u62df\\u4f18\\u70b91\"],"
        + "\"teachingAdvice\":\"\\u6a21\\u62df\\u6559\\u5b66\\u5efa\\u8bae\\u3002\","
        + "\"scoreDistribution\":{\"high\":3,\"medium\":10,\"low\":2}}";
  }
}
