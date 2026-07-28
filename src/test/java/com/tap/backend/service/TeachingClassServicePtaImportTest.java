package com.tap.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.ClassAssignmentCleanupRepository;
import com.tap.backend.repo.ClassStudentRepository;
import com.tap.backend.repo.TeachingClassRepository;
import com.tap.backend.repo.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class TeachingClassServicePtaImportTest {

    @Test
    void importStudentsUsesAuthoritativeUserGroupRosterAndCreatesClassStudent() {
        TeachingClassRepository classRepo = mock(TeachingClassRepository.class);
        ClassStudentRepository studentRepo = mock(ClassStudentRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PtaUserGroupRosterService rosterService = mock(PtaUserGroupRosterService.class);
        ClassMemberStatsService statsService = mock(ClassMemberStatsService.class);
        TeachingClassDeletionGuard deletionGuard = mock(TeachingClassDeletionGuard.class);
        ClassAssignmentCleanupRepository cleanupRepository = mock(ClassAssignmentCleanupRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TeachingClassService service = new TeachingClassService(
                classRepo,
                studentRepo,
                userRepo,
                passwordEncoder,
                rosterService,
                statsService,
                deletionGuard,
                cleanupRepository,
                jdbcTemplate
        );
        TeachingClassEntity teachingClass = mock(TeachingClassEntity.class);
        when(teachingClass.getId()).thenReturn(10L);
        when(teachingClass.getTeacherId()).thenReturn(7L);
        when(teachingClass.getName()).thenReturn("数据结构一班");
        when(teachingClass.getPtaGroupId()).thenReturn("group-a");
        when(teachingClass.getPtaGroupName()).thenReturn("数据结构用户组");
        when(classRepo.findById(10L)).thenReturn(Optional.of(teachingClass));
        when(rosterService.findActiveRoster(10L, "group-a")).thenReturn(List.of(
                new PtaUserGroupRosterService.RosterStudent("20240001", "张三", 1000L)
        ));
        when(studentRepo.findByClassIdAndStudentNum(10L, "20240001")).thenReturn(Optional.empty());
        when(studentRepo.save(org.mockito.ArgumentMatchers.any(ClassStudentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = service.importStudentsFromPta(10L, 7L);

        assertThat(result.get("matchedStudentCount")).isEqualTo(1);
        assertThat(result.get("createdCount")).isEqualTo(1);
        verify(studentRepo).save(org.mockito.ArgumentMatchers.argThat(student ->
                student.getTeachingClass() == teachingClass
                        && "20240001".equals(student.getStudentNum())
                        && "张三".equals(student.getStudentName())
                        && Long.valueOf(1000L).equals(student.getUserId())
        ));
    }
}
