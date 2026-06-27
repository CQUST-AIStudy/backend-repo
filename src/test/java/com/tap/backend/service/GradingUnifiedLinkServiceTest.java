package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.SubmissionMatchStatus;
import com.tap.backend.domain.user.UserEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GradingUnifiedLinkServiceTest {

    @Test
    void confirmsStudentByFilenameNumberInsideRoster() {
        var service = serviceWithRoster(List.of(
                identity(1L, "2023440415", "邹名格"),
                identity(2L, "2023440416", "李明")
        ));
        var submission = submission("2023440415-邹名格-指针实验.pdf");

        var result = service.resolveSubmissionMatch(task(), submission);

        assertEquals(SubmissionMatchStatus.AUTO_CONFIRMED, result.status());
        assertEquals(1L, result.identity().studentProfileId());
    }

    @Test
    void confirmsUniqueNameInsideRoster() {
        var service = serviceWithRoster(List.of(
                identity(1L, "2023440415", "邹名格"),
                identity(2L, "2023440416", "李明")
        ));

        var result = service.resolveSubmissionMatch(task(), submission("邹名格-指针实验.pdf"));

        assertEquals(SubmissionMatchStatus.AUTO_CONFIRMED, result.status());
        assertEquals(1L, result.identity().studentProfileId());
    }

    @Test
    void marksDuplicateNameAsAmbiguous() {
        var service = serviceWithRoster(List.of(
                identity(1L, "2023440415", "李明"),
                identity(2L, "2023440416", "李明")
        ));

        var result = service.resolveSubmissionMatch(task(), submission("李明-指针实验.pdf"));

        assertEquals(SubmissionMatchStatus.AMBIGUOUS, result.status());
        assertNull(result.identity());
        assertEquals(2, result.candidates().size());
    }

    @Test
    void loadsRosterFromTeachersOnlyClassWhenHistoricalTaskHasNoClassId() {
        var expected = List.of(identity(1L, "20230001", "陈一鸣"));
        var service = new GradingUnifiedLinkService(mock(JdbcTemplate.class), new GradingFilenameIdentityParser()) {
            @Override
            protected Long findSoleOwnedClassId(Long teacherId) {
                assertEquals(1L, teacherId);
                return 10L;
            }

            @Override
            protected List<SubmissionIdentity> loadRosterByClassId(Long classId) {
                assertEquals(10L, classId);
                return expected;
            }
        };
        GradingTaskEntity task = new GradingTaskEntity();
        UserEntity teacher = mock(UserEntity.class);
        when(teacher.getId()).thenReturn(1L);
        task.setTeacher(teacher);

        assertEquals(expected, service.listRoster(task));
    }

    private GradingUnifiedLinkService serviceWithRoster(
            List<GradingUnifiedLinkService.SubmissionIdentity> roster) {
        return new GradingUnifiedLinkService(mock(JdbcTemplate.class), new GradingFilenameIdentityParser()) {
            @Override
            public List<SubmissionIdentity> listRoster(GradingTaskEntity task) {
                return roster;
            }
        };
    }

    private GradingTaskEntity task() {
        GradingTaskEntity task = new GradingTaskEntity();
        task.setClassId(10L);
        return task;
    }

    private GradingSubmissionEntity submission(String filename) {
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setOriginalFilename(filename);
        submission.setStudentName(filename.replaceFirst("\\.[^.]+$", ""));
        return submission;
    }

    private GradingUnifiedLinkService.SubmissionIdentity identity(Long id, String number, String name) {
        return new GradingUnifiedLinkService.SubmissionIdentity(
                id, number, name, "计科23班", name, Integer.parseInt(number));
    }
}
