package com.tap.backend.rag;

import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.rag.lc4j.dto.TeacherRagCitationView;
import com.tap.backend.rag.lc4j.dto.TeacherRagExecutionResult;
import com.tap.backend.rag.lc4j.service.TeacherRagFacade;
import com.tap.backend.service.CourseSpaceService;
import java.io.OutputStream;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RagOrchestratorService {

    private final TeacherRagFacade teacherRagFacade;

    public RagOrchestratorService(TeacherRagFacade teacherRagFacade) {
        this.teacherRagFacade = teacherRagFacade;
    }

    public record RagResult(
            String answerText,
            List<CitationInfo> citations,
            List<Long> retrievedChunkIds,
            double top1Score,
            double coverageScore,
            String intentType,
            String effectiveMode,
            boolean usedWeb
    ) {}

    public record CitationInfo(int index, String docName, String chapterPath,
                               String pageRange, double score, String source)
            implements TeacherRagCitationView {}

    public RagResult execute(long courseSpaceId, String query, String requestedMode,
                             CourseSpaceEntity courseSpace,
                             CourseSpaceService.RagChatScope chatScope,
                             OutputStream outputStream) {
        TeacherRagExecutionResult result =
                teacherRagFacade.execute(
                        courseSpaceId, query, requestedMode, courseSpace, chatScope, outputStream);
        return new RagResult(
                result.answerText(),
                result.citations().stream()
                        .map(citation -> new CitationInfo(
                                citation.index(),
                                citation.docName(),
                                citation.chapterPath(),
                                citation.pageRange(),
                                citation.score(),
                                citation.source()))
                        .toList(),
                result.retrievedChunkIds(),
                result.top1Score(),
                result.coverageScore(),
                result.intentType(),
                result.effectiveMode(),
                result.usedWeb());
    }
}
