package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.ClassStudentRepository;
import com.tap.backend.repo.ClassAssignmentCleanupRepository;
import com.tap.backend.repo.TeachingClassRepository;
import com.tap.backend.repo.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TeachingClassServiceTest {

    @Mock
    private TeachingClassRepository classRepo;

    @Mock
    private ClassStudentRepository studentRepo;

    @Mock
    private UserRepository userRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LegacyPtaRosterService legacyPtaRosterService;

    @Mock
    private ClassMemberStatsService classMemberStatsService;

    @Mock
    private TeachingClassDeletionGuard teachingClassDeletionGuard;

    @Mock
    private ClassAssignmentCleanupRepository classAssignmentCleanupRepository;

    private TeachingClassService service;

    @BeforeEach
    void setUp() {
        service = new TeachingClassService(
                classRepo,
                studentRepo,
                userRepo,
                passwordEncoder,
                legacyPtaRosterService,
                classMemberStatsService,
                teachingClassDeletionGuard,
                classAssignmentCleanupRepository
        );
    }

    @Test
    void deleteClassRejectsWhenAssignmentOfferingExists() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);
        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));
        when(teachingClassDeletionGuard.hasBlockingReferences(1L)).thenReturn(true);

        ClassDeletionBlockedException error = assertThrows(
                ClassDeletionBlockedException.class,
                () -> service.deleteClass(1L, 10L)
        );

        assertEquals("class is still referenced by published assignments and cannot be deleted", error.getMessage());
        verify(classAssignmentCleanupRepository, never()).deleteAssignmentDataByClassId(1L);
        verify(classRepo, never()).delete(any());
    }

    @Test
    void deleteClassForceCleansAssignmentDataBeforeDeletingClass() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);
        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));
        when(teachingClassDeletionGuard.hasBlockingReferences(1L)).thenReturn(true);

        service.deleteClass(1L, 10L, true);

        verify(classAssignmentCleanupRepository).deleteAssignmentDataByClassId(1L);
        verify(classRepo).delete(teachingClass);
    }

    @Test
    void addStudentForTeacherResolvesStudentAccountFromStudentNum() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);
        UserEntity studentUser = studentUser(88L, "20250001", true);

        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));
        when(userRepo.findByUsername("20250001")).thenReturn(Optional.of(studentUser));
        when(studentRepo.existsByClassIdAndStudentNum(1L, "20250001")).thenReturn(false);
        when(studentRepo.save(any(ClassStudentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassStudentEntity saved = service.addStudentForTeacher(1L, 10L, "  张三  ", " 20250001 ", null);

        assertEquals("张三", saved.getStudentName());
        assertEquals("20250001", saved.getStudentNum());
        assertEquals(88L, saved.getUserId());

        ArgumentCaptor<ClassStudentEntity> captor = ArgumentCaptor.forClass(ClassStudentEntity.class);
        verify(studentRepo).save(captor.capture());
        ClassStudentEntity persisted = captor.getValue();
        assertEquals("张三", persisted.getStudentName());
        assertEquals("20250001", persisted.getStudentNum());
        assertEquals(88L, persisted.getUserId());
        assertEquals(teachingClass, persisted.getTeachingClass());
    }

    @Test
    void addStudentForTeacherRejectsWhenStudentAccountMissing() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);

        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));
        when(userRepo.findByUsername("20250001")).thenReturn(Optional.empty());
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.addStudentForTeacher(1L, 10L, "张三", "20250001", null)
        );

        assertEquals("学生账号不存在，不需要添加", error.getMessage());
        verify(studentRepo, never()).save(any());
    }

    @Test
    void addStudentForTeacherRejectsBlankStudentNum() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);
        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.addStudentForTeacher(1L, 10L, "张三", "   ", null)
        );

        assertEquals("studentNum is required when adding a student manually", error.getMessage());
        verify(userRepo, never()).findByUsername(any());
        verify(studentRepo, never()).save(any());
    }

    @Test
    void addStudentRejectsBlankStudentNameBeforeSaving() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.addStudent(1L, "   ", "20250001", 88L)
        );

        assertEquals("studentName is required", error.getMessage());
        verify(classRepo, never()).findById(any());
        verify(studentRepo, never()).save(any());
    }

    @Test
    void addStudentAllowsBlankStudentNumAndStoresNull() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);

        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));
        when(studentRepo.save(any(ClassStudentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClassStudentEntity saved = service.addStudent(1L, " 张三 ", "   ", 88L);

        assertEquals("张三", saved.getStudentName());
        assertNull(saved.getStudentNum());
        assertEquals(88L, saved.getUserId());
        verify(studentRepo, never()).existsByClassIdAndStudentNum(any(), any());
    }

    @Test
    void listClassesByStudentCombinesUserIdAndStudentNumBindings() {
        TeachingClassEntity userBoundClass = teachingClass(1L, 10L);
        TeachingClassEntity numberBoundClass = teachingClass(2L, 10L);

        when(studentRepo.findAllByUserId(88L)).thenReturn(List.of(classStudent(1L, 88L, null)));
        when(studentRepo.findAllByStudentNum("20250001")).thenReturn(List.of(classStudent(2L, null, "20250001")));
        when(classRepo.findAllById(any())).thenReturn(List.of(userBoundClass, numberBoundClass));

        List<TeachingClassEntity> classes = service.listClassesByStudent(88L, " 20250001 ");

        assertEquals(2, classes.size());
        assertEquals(1L, classes.get(0).getId());
        assertEquals(2L, classes.get(1).getId());
    }

    @Test
    void listClassesByStudentBindsUnboundRosterRowsByStudentNum() {
        TeachingClassEntity numberBoundClass = teachingClass(2L, 10L);
        ClassStudentEntity unboundRosterRow = classStudent(2L, null, "20250001");

        when(studentRepo.findAllByUserId(88L)).thenReturn(List.of());
        when(studentRepo.findAllByStudentNum("20250001")).thenReturn(List.of(unboundRosterRow));
        when(studentRepo.save(any(ClassStudentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(classRepo.findAllById(any())).thenReturn(List.of(numberBoundClass));

        List<TeachingClassEntity> classes = service.listClassesByStudent(88L, "20250001");

        assertEquals(1, classes.size());
        assertEquals(2L, classes.get(0).getId());
        assertEquals(88L, unboundRosterRow.getUserId());
        verify(studentRepo).save(unboundRosterRow);
    }

    @Test
    void bindStudentAccountByStudentNumBindsOnlyUnboundRows() {
        ClassStudentEntity unboundRosterRow = classStudent(2L, null, "20250001");
        ClassStudentEntity alreadyBoundRow = classStudent(3L, 77L, "20250001");

        when(studentRepo.findAllByStudentNum("20250001")).thenReturn(List.of(unboundRosterRow, alreadyBoundRow));
        when(studentRepo.save(any(ClassStudentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int boundCount = service.bindStudentAccountByStudentNum(88L, " 20250001 ");

        assertEquals(1, boundCount);
        assertEquals(88L, unboundRosterRow.getUserId());
        assertEquals(77L, alreadyBoundRow.getUserId());
        verify(studentRepo).save(unboundRosterRow);
    }

    @Test
    void importStudentsFromPtaBindsExistingStudentAccountWhenCreatingRosterRow() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);
        teachingClass.setName("Software 2023-02");
        teachingClass.setPtaKeyword("Software");
        UserEntity studentUser = studentUser(88L, "20250001", true);

        when(classRepo.findById(1L)).thenReturn(Optional.of(teachingClass));
        when(legacyPtaRosterService.findRoster("Software 2023-02", "Software"))
                .thenReturn(List.of(new LegacyPtaRosterService.RosterStudent("20250001", "Alice")));
        when(userRepo.findByUsername("20250001")).thenReturn(Optional.of(studentUser));
        when(studentRepo.findByClassIdAndStudentNum(1L, "20250001")).thenReturn(Optional.empty());
        when(studentRepo.save(any(ClassStudentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = service.importStudentsFromPta(1L, 10L);

        assertEquals(1, result.get("createdCount"));
        assertEquals(0, result.get("updatedCount"));
        ArgumentCaptor<ClassStudentEntity> captor = ArgumentCaptor.forClass(ClassStudentEntity.class);
        verify(studentRepo).save(captor.capture());
        assertEquals("20250001", captor.getValue().getStudentNum());
        assertEquals("Alice", captor.getValue().getStudentName());
        assertEquals(88L, captor.getValue().getUserId());
    }

    @Test
    void joinClassRejectsWrongPasswordWithDedicatedException() {
        TeachingClassEntity teachingClass = teachingClass(1L, 10L);
        teachingClass.setJoinPassword("right-pass");

        when(classRepo.findByClassCode("SOFT2023")).thenReturn(Optional.of(teachingClass));

        InvalidClassPasswordException error = assertThrows(
                InvalidClassPasswordException.class,
                () -> service.joinClass("SOFT2023", "wrong-pass", "Alice", "20250001", 88L)
        );

        assertEquals("班级密码错误", error.getMessage());
        verify(studentRepo, never()).save(any());
    }

    private TeachingClassEntity teachingClass(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = new TeachingClassEntity();
        ReflectionTestUtils.setField(teachingClass, "id", classId);
        ReflectionTestUtils.setField(teachingClass, "teacherId", teacherId);
        return teachingClass;
    }

    private ClassStudentEntity classStudent(Long classId, Long userId, String studentNum) {
        ClassStudentEntity student = new ClassStudentEntity();
        ReflectionTestUtils.setField(student, "classId", classId);
        student.setUserId(userId);
        student.setStudentNum(studentNum);
        return student;
    }

    private UserEntity studentUser(Long userId, String username, boolean enabled) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setUsername(username);
        user.setRole(UserRole.STUDENT);
        user.setEnabled(enabled);
        return user;
    }
}
