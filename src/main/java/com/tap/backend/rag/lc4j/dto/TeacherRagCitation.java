package com.tap.backend.rag.lc4j.dto;

public record TeacherRagCitation(
    int index,
    String docName,
    String chapterPath,
    String pageRange,
    double score,
    String source) implements TeacherRagCitationView {}
