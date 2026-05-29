package com.tap.backend.rag.lc4j.dto;

public interface TeacherRagCitationView {

  int index();

  String docName();

  String chapterPath();

  String pageRange();

  double score();

  String source();
}
