package com.tap.backend.academic.learningtracking;

import java.util.List;

public class LearningTrackingResponse {
    private String studentId;
    private String studentName;
    private LearningTrackingSummary summary;
    private List<TimelineEntry> timeline;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    public LearningTrackingSummary getSummary() { return summary; }
    public void setSummary(LearningTrackingSummary summary) { this.summary = summary; }
    public List<TimelineEntry> getTimeline() { return timeline; }
    public void setTimeline(List<TimelineEntry> timeline) { this.timeline = timeline; }

    public static class LearningTrackingSummary {
        private List<PtaPracticeSetSummary> ptaPracticeSets;
        private int ptaTotalSets;
        private int ptaCompletedSets;
        private LeetCodePracticeSummary leetcode;

        public List<PtaPracticeSetSummary> getPtaPracticeSets() { return ptaPracticeSets; }
        public void setPtaPracticeSets(List<PtaPracticeSetSummary> ptaPracticeSets) { this.ptaPracticeSets = ptaPracticeSets; }
        public int getPtaTotalSets() { return ptaTotalSets; }
        public void setPtaTotalSets(int ptaTotalSets) { this.ptaTotalSets = ptaTotalSets; }
        public int getPtaCompletedSets() { return ptaCompletedSets; }
        public void setPtaCompletedSets(int ptaCompletedSets) { this.ptaCompletedSets = ptaCompletedSets; }
        public LeetCodePracticeSummary getLeetcode() { return leetcode; }
        public void setLeetcode(LeetCodePracticeSummary leetcode) { this.leetcode = leetcode; }
    }
}
