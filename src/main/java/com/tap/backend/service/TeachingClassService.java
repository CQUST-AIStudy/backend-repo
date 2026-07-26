package com.tap.backend.service;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.ClassStudentRepository;
import com.tap.backend.repo.ClassAssignmentCleanupRepository;
import com.tap.backend.repo.TeachingClassRepository;
import com.tap.backend.repo.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeachingClassService {

    private final TeachingClassRepository classRepo;
    private final ClassStudentRepository studentRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final LegacyPtaRosterService legacyPtaRosterService;
    private final ClassMemberStatsService classMemberStatsService;
    private final TeachingClassDeletionGuard teachingClassDeletionGuard;
    private final ClassAssignmentCleanupRepository classAssignmentCleanupRepository;

    public TeachingClassService(
            TeachingClassRepository classRepo,
            ClassStudentRepository studentRepo,
            UserRepository userRepo,
            PasswordEncoder passwordEncoder,
            LegacyPtaRosterService legacyPtaRosterService,
            ClassMemberStatsService classMemberStatsService,
            TeachingClassDeletionGuard teachingClassDeletionGuard,
            ClassAssignmentCleanupRepository classAssignmentCleanupRepository
    ) {
        this.classRepo = classRepo;
        this.studentRepo = studentRepo;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.legacyPtaRosterService = legacyPtaRosterService;
        this.classMemberStatsService = classMemberStatsService;
        this.teachingClassDeletionGuard = teachingClassDeletionGuard;
        this.classAssignmentCleanupRepository = classAssignmentCleanupRepository;
    }

    public record StudentAccountImportItem(
            String username,
            String studentName,
            String studentNum,
            String password
    ) {}

    public record StudentAccountImportRow(
            int rowIndex,
            String username,
            String studentName,
            String studentNum,
            Long userId,
            Long classStudentId,
            String action,
            String message
    ) {}

    public record StudentAccountImportResult(
            Long classId,
            String className,
            int totalCount,
            int createdUserCount,
            int reusedUserCount,
            int createdClassStudentCount,
            int updatedClassStudentCount,
            int skippedCount,
            List<StudentAccountImportRow> rows
    ) {}

    @Transactional(readOnly = true)
    public List<TeachingClassEntity> listByTeacher(Long teacherId) {
        return classRepo.findAllByTeacherId(teacherId);
    }

    @Transactional(readOnly = true)
    public List<TeachingClassEntity> listAll() {
        return classRepo.findAll();
    }

    @Transactional
    public TeachingClassEntity createClass(
            UserEntity teacher,
            String name,
            String classCode,
            String joinPassword,
            String grade,
            String courseName,
            Long courseId,
            Long termId,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {
        if (classRepo.existsByClassCode(classCode)) {
            throw new IllegalArgumentException("class code already exists: " + classCode);
        }
        TeachingClassEntity teachingClass = new TeachingClassEntity();
        teachingClass.setTeacher(teacher);
        teachingClass.setName(name);
        teachingClass.setClassCode(classCode);
        teachingClass.setJoinPassword(joinPassword);
        teachingClass.setGrade(grade);
        teachingClass.setCourseName(courseName);
        teachingClass.setCourseId(courseId);
        teachingClass.setTermId(termId);
        teachingClass.setDescription(description);
        String resolvedGroupName = normalizeNullableText(firstNotBlank(ptaGroupName, ptaKeyword));
        teachingClass.setPtaGroupName(resolvedGroupName);
        teachingClass.setPtaKeyword(resolvePtaKeyword(name, firstNotBlank(resolvedGroupName, ptaKeyword)));
        if (syncEnabled != null) {
            teachingClass.setSyncEnabled(syncEnabled);
        }
        teachingClass = classRepo.save(teachingClass);
        // 在事务内触发 teacher 懒加载代理初始化，避免 Controller 端 LazyInitializationException
        teachingClass.getTeacher().getDisplayName();
        return teachingClass;
    }

    @Transactional
    public TeachingClassEntity updateClass(
            Long classId,
            Long teacherId,
            String name,
            String joinPassword,
            String grade,
            String courseName,
            Long courseId,
            Long termId,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        if (name != null) {
            teachingClass.setName(name);
        }
        if (joinPassword != null) {
            teachingClass.setJoinPassword(joinPassword);
        }
        if (grade != null) {
            teachingClass.setGrade(grade);
        }
        if (courseName != null) {
            teachingClass.setCourseName(courseName);
        }
        if (courseId != null) {
            teachingClass.setCourseId(courseId);
        }
        if (termId != null) {
            teachingClass.setTermId(termId);
        }
        if (description != null) {
            teachingClass.setDescription(description);
        }
        if (ptaKeyword != null) {
            String resolvedKeyword = normalizeNullableText(ptaKeyword);
            teachingClass.setPtaKeyword(resolvePtaKeyword(teachingClass.getName(), resolvedKeyword));
            if (teachingClass.getPtaGroupName() == null || teachingClass.getPtaGroupName().isBlank()) {
                teachingClass.setPtaGroupName(resolvedKeyword);
            }
        }
        if (ptaGroupName != null) {
            String resolvedGroupName = normalizeNullableText(ptaGroupName);
            teachingClass.setPtaGroupName(resolvedGroupName);
            teachingClass.setPtaKeyword(resolvePtaKeyword(teachingClass.getName(), resolvedGroupName));
        }
        if (syncEnabled != null) {
            teachingClass.setSyncEnabled(syncEnabled);
        }
        teachingClass = classRepo.save(teachingClass);
        // 在事务内触发 teacher 懒加载代理初始化，避免 Controller 端 LazyInitializationException
        teachingClass.getTeacher().getDisplayName();
        return teachingClass;
    }

    @Transactional
    public void deleteClass(Long classId, Long teacherId) {
        deleteClass(classId, teacherId, false);
    }

    @Transactional
    public void deleteClass(Long classId, Long teacherId, boolean force) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        if (teachingClassDeletionGuard.hasBlockingReferences(classId) && !force) {
            throw new ClassDeletionBlockedException("class is still referenced by published assignments and cannot be deleted");
        }
        if (force) {
            classAssignmentCleanupRepository.deleteAssignmentDataByClassId(classId);
        }
        classRepo.delete(teachingClass);
    }

    @Transactional(readOnly = true)
    public List<ClassStudentEntity> listStudents(Long classId) {
        return studentRepo.findAllByClassId(classId);
    }

    @Transactional(readOnly = true)
    public List<ClassStudentEntity> listStudentsForTeacher(Long classId, Long teacherId) {
        requireOwnedClass(classId, teacherId);
        return studentRepo.findAllByClassId(classId);
    }

    @Transactional(readOnly = true)
    public ClassStudentEntity getStudentForTeacher(Long classId, Long studentId, Long teacherId) {
        requireOwnedClass(classId, teacherId);
        return studentRepo.findByIdAndClassId(studentId, classId)
                .orElseThrow(() -> new NoSuchElementException("student not found"));
    }

    @Transactional
    public ClassStudentEntity addStudent(Long classId, String studentName, String studentNum, Long userId) {
        String normalizedStudentName = blankToNull(studentName);
        if (normalizedStudentName == null) {
            throw new IllegalArgumentException("studentName is required");
        }
        String normalizedStudentNum = blankToNull(studentNum);
        if (normalizedStudentNum != null && studentRepo.existsByClassIdAndStudentNum(classId, normalizedStudentNum)) {
            throw new IllegalArgumentException("student number already exists in this class");
        }
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        ClassStudentEntity student = new ClassStudentEntity();
        student.setTeachingClass(teachingClass);
        student.setStudentName(normalizedStudentName);
        student.setStudentNum(normalizedStudentNum);
        student.setUserId(userId);
        return studentRepo.save(student);
    }

    @Transactional
    public ClassStudentEntity addStudentForTeacher(
            Long classId,
            Long teacherId,
            String studentName,
            String studentNum,
            Long userId
    ) {
        requireOwnedClass(classId, teacherId);
        String normalizedStudentNum = blankToNull(studentNum);
        if (normalizedStudentNum == null) {
            throw new IllegalArgumentException("studentNum is required when adding a student manually");
        }
        Long resolvedUserId = userId == null ? resolveRequiredStudentUserId(normalizedStudentNum) : userId;
        return addStudent(classId, studentName, normalizedStudentNum, resolvedUserId);
    }

    @Transactional
    public StudentAccountImportResult importStudentAccountsForTeacher(
            Long classId,
            Long teacherId,
            List<StudentAccountImportItem> items,
            String defaultPassword
    ) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("student import rows cannot be empty");
        }
        if (items.size() > 1000) {
            throw new IllegalArgumentException("student import rows cannot exceed 1000");
        }

        String normalizedDefaultPassword = blankToNull(defaultPassword);
        List<StudentAccountImportRow> rows = new ArrayList<>();
        Set<String> seenUsernames = new LinkedHashSet<>();
        Set<String> seenStudentNums = new LinkedHashSet<>();
        int createdUserCount = 0;
        int reusedUserCount = 0;
        int createdClassStudentCount = 0;
        int updatedClassStudentCount = 0;
        int skippedCount = 0;

        for (int index = 0; index < items.size(); index++) {
            StudentAccountImportItem item = items.get(index);
            int rowIndex = index + 1;
            String username = normalizeUsername(item == null ? null : item.username());
            String studentName = normalizeRequiredText(item == null ? null : item.studentName(), "studentName");
            String studentNum = blankToNull(item == null ? null : item.studentNum());
            String password = blankToNull(item == null ? null : item.password());
            if (password == null) {
                password = normalizedDefaultPassword;
            }

            if (username == null) {
                rows.add(new StudentAccountImportRow(rowIndex, null, studentName, studentNum, null, null, "SKIPPED", "username is required"));
                skippedCount++;
                continue;
            }
            if (studentName == null) {
                rows.add(new StudentAccountImportRow(rowIndex, username, null, studentNum, null, null, "SKIPPED", "studentName is required"));
                skippedCount++;
                continue;
            }
            if (password == null || password.length() < 6) {
                rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, null, null, "SKIPPED", "password must be at least 6 characters"));
                skippedCount++;
                continue;
            }
            if (!seenUsernames.add(username)) {
                rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, null, null, "SKIPPED", "duplicate username in request"));
                skippedCount++;
                continue;
            }
            if (studentNum != null && !seenStudentNums.add(studentNum)) {
                rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, null, null, "SKIPPED", "duplicate studentNum in request"));
                skippedCount++;
                continue;
            }

            UserEntity user = userRepo.findByUsername(username).orElse(null);
            boolean createdUser = false;
            if (user == null) {
                user = new UserEntity();
                user.setUsername(username);
                user.setDisplayName(studentName);
                user.setRole(UserRole.STUDENT);
                user.setPasswordHash(passwordEncoder.encode(password));
                user.setEnabled(true);
                user = userRepo.save(user);
                createdUser = true;
                createdUserCount++;
            } else {
                if (user.getRole() != UserRole.STUDENT) {
                    rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, user.getId(), null, "SKIPPED", "username exists but role is not STUDENT"));
                    skippedCount++;
                    continue;
                }
                if (!Boolean.TRUE.equals(user.getEnabled())) {
                    user.setEnabled(true);
                }
                if (blankToNull(user.getDisplayName()) == null) {
                    user.setDisplayName(studentName);
                }
                user = userRepo.save(user);
                reusedUserCount++;
            }

            ClassStudentEntity classStudent = null;
            if (studentNum != null) {
                classStudent = studentRepo.findByClassIdAndStudentNum(classId, studentNum).orElse(null);
            }
            if (classStudent == null) {
                classStudent = new ClassStudentEntity();
                classStudent.setTeachingClass(teachingClass);
                classStudent.setStudentNum(studentNum);
                classStudent.setStudentName(studentName);
                classStudent.setUserId(user.getId());
                classStudent = studentRepo.save(classStudent);
                createdClassStudentCount++;
                rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, user.getId(), classStudent.getId(), createdUser ? "CREATED" : "BOUND", null));
            } else {
                if (classStudent.getUserId() != null && !classStudent.getUserId().equals(user.getId())) {
                    rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, user.getId(), classStudent.getId(), "SKIPPED", "studentNum is already bound to another user"));
                    skippedCount++;
                    continue;
                }
                classStudent.setStudentName(studentName);
                classStudent.setUserId(user.getId());
                classStudent = studentRepo.save(classStudent);
                updatedClassStudentCount++;
                rows.add(new StudentAccountImportRow(rowIndex, username, studentName, studentNum, user.getId(), classStudent.getId(), createdUser ? "CREATED_AND_UPDATED" : "UPDATED", null));
            }
        }

        return new StudentAccountImportResult(
                teachingClass.getId(),
                teachingClass.getName(),
                items.size(),
                createdUserCount,
                reusedUserCount,
                createdClassStudentCount,
                updatedClassStudentCount,
                skippedCount,
                rows
        );
    }

    @Transactional
    public UserEntity createStudentAccountForTeacher(Long classId, Long studentRecordId, Long teacherId, String rawPassword) {
        ClassStudentEntity student = getStudentForTeacher(classId, studentRecordId, teacherId);
        String studentNum = blankToNull(student.getStudentNum());
        if (studentNum == null) {
            throw new IllegalStateException("该学生没有学号，无法自动创建账号");
        }
        UserEntity account = userRepo.findByUsername(studentNum).orElse(null);
        if (account == null) {
            account = new UserEntity();
            account.setUsername(studentNum);
            account.setUsernum(studentNum);
            account.setDisplayName(student.getStudentName());
            account.setRole(UserRole.STUDENT);
            account.setPasswordHash(passwordEncoder.encode(rawPassword));
            account.setEnabled(true);
            account = userRepo.save(account);
        } else {
            if (account.getRole() != UserRole.STUDENT) {
                throw new IllegalStateException("用户名 " + studentNum + " 已被非学生账号占用，无法自动创建账号");
            }
            account.setPasswordHash(passwordEncoder.encode(rawPassword));
            if (!Boolean.TRUE.equals(account.getEnabled())) {
                account.setEnabled(true);
            }
            if (blankToNull(account.getDisplayName()) == null) {
                account.setDisplayName(student.getStudentName());
            }
            account = userRepo.save(account);
        }
        student.setUserId(account.getId());
        studentRepo.save(student);
        return account;
    }

    @Transactional
    public void removeStudent(Long studentRecordId) {
        studentRepo.deleteById(studentRecordId);
    }

    @Transactional
    public void removeStudentForTeacher(Long classId, Long studentRecordId, Long teacherId) {
        requireOwnedClass(classId, teacherId);
        ClassStudentEntity student = studentRepo.findById(studentRecordId)
                .orElseThrow(() -> new NoSuchElementException("student not found"));
        if (!classId.equals(student.getClassId())) {
            throw new NoSuchElementException("student not found");
        }
        studentRepo.delete(student);
    }

    @Transactional
    public ClassStudentEntity joinClass(
            String classCode,
            String password,
            String studentName,
            String studentNum,
            Long userId
    ) {
        TeachingClassEntity teachingClass = classRepo.findByClassCode(classCode)
                .orElseThrow(() -> new NoSuchElementException("class code not found"));
        if (!teachingClass.getJoinPassword().equals(password)) {
            throw new InvalidClassPasswordException();
        }
        String normalizedStudentNum = studentNum == null ? null : studentNum.trim();
        if (normalizedStudentNum != null && !normalizedStudentNum.isBlank()) {
            var existing = studentRepo.findByClassIdAndStudentNum(teachingClass.getId(), normalizedStudentNum);
            if (existing.isPresent()) {
                ClassStudentEntity matched = existing.get();
                if (matched.getUserId() != null && userId != null && !userId.equals(matched.getUserId())) {
                    throw new IllegalArgumentException("student already joined this class");
                }
                if (matched.getUserId() == null && userId != null) {
                    matched.setUserId(userId);
                }
                if (studentName != null && !studentName.isBlank()) {
                    matched.setStudentName(studentName);
                }
                return studentRepo.save(matched);
            }
        }
        ClassStudentEntity student = new ClassStudentEntity();
        student.setTeachingClass(teachingClass);
        student.setStudentName(studentName);
        student.setStudentNum(normalizedStudentNum);
        student.setUserId(userId);
        return studentRepo.save(student);
    }

    @Transactional(readOnly = true)
    public long countStudents(Long classId) {
        return classMemberStatsService.countActiveStudents(classId);
    }

    @Transactional(readOnly = true)
    public long countBoundStudents(Long classId) {
        return classMemberStatsService.countActiveStudentsBoundToUsers(classId);
    }

    @Transactional(readOnly = true)
    public List<ClassStudentEntity> listClassesByUser(Long userId) {
        return studentRepo.findAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<TeachingClassEntity> listClassesByStudentNum(String studentNum) {
        if (studentNum == null || studentNum.isBlank()) {
            return List.of();
        }
        Set<Long> classIds = studentRepo.findAllByStudentNum(studentNum.trim()).stream()
                .map(ClassStudentEntity::getClassId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) {
            return List.of();
        }
        return classRepo.findAllById(classIds);
    }

    @Transactional
    public List<TeachingClassEntity> listClassesByStudent(Long userId, String studentNum) {
        Set<Long> classIds = new LinkedHashSet<>();
        if (userId != null) {
            studentRepo.findAllByUserId(userId).stream()
                    .map(ClassStudentEntity::getClassId)
                    .forEach(classIds::add);
        }
        String normalizedStudentNum = blankToNull(studentNum);
        if (normalizedStudentNum != null) {
            studentRepo.findAllByStudentNum(normalizedStudentNum).stream()
                    .peek(student -> bindStudentAccountIfNeeded(student, userId))
                    .map(ClassStudentEntity::getClassId)
                    .forEach(classIds::add);
        }
        if (classIds.isEmpty()) {
            return List.of();
        }
        return classRepo.findAllById(classIds);
    }

    @Transactional
    public int bindStudentAccountByStudentNum(Long userId, String studentNum) {
        if (userId == null) {
            return 0;
        }
        String normalizedStudentNum = blankToNull(studentNum);
        if (normalizedStudentNum == null) {
            return 0;
        }

        int boundCount = 0;
        for (ClassStudentEntity student : studentRepo.findAllByStudentNum(normalizedStudentNum)) {
            if (student.getUserId() != null) {
                continue;
            }
            student.setUserId(userId);
            studentRepo.save(student);
            boundCount++;
        }
        return boundCount;
    }

    @Transactional
    public java.util.Map<String, Object> importStudentsFromPta(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        List<LegacyPtaRosterService.RosterStudent> roster = legacyPtaRosterService.findRoster(
                teachingClass.getName(),
                teachingClass.getPtaKeyword()
        );

        int createdCount = 0;
        int updatedCount = 0;
        int unchangedCount = 0;

        for (LegacyPtaRosterService.RosterStudent item : roster) {
            String studentNum = blankToNull(item.studentNum());
            String studentName = blankToNull(item.studentName());
            Long userId = findActiveStudentUserIdByUsername(studentNum);
            var existing = studentNum == null
                    ? Optional.<ClassStudentEntity>empty()
                    : studentRepo.findByClassIdAndStudentNum(classId, studentNum);
            if (existing.isPresent()) {
                ClassStudentEntity student = existing.get();
                boolean changed = false;
                if (studentName != null && !studentName.equals(student.getStudentName())) {
                    student.setStudentName(studentName);
                    changed = true;
                }
                if (student.getUserId() == null && userId != null) {
                    student.setUserId(userId);
                    changed = true;
                }
                if (changed) {
                    studentRepo.save(student);
                    updatedCount++;
                } else {
                    unchangedCount++;
                }
                continue;
            }

            ClassStudentEntity student = new ClassStudentEntity();
            student.setTeachingClass(teachingClass);
            student.setStudentNum(studentNum);
            student.setStudentName(studentName);
            student.setUserId(userId);
            studentRepo.save(student);
            createdCount++;
        }

        java.util.Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", teachingClass.getId());
        result.put("className", teachingClass.getName());
        result.put("ptaKeyword", teachingClass.getPtaKeyword());
        result.put("ptaGroupName", teachingClass.getPtaGroupName());
        result.put("matchedStudentCount", roster.size());
        result.put("createdCount", createdCount);
        result.put("updatedCount", updatedCount);
        result.put("unchangedCount", unchangedCount);
        return result;
    }

    private TeachingClassEntity requireOwnedClass(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        if (!teacherId.equals(teachingClass.getTeacherId())) {
            throw new SecurityException("forbidden");
        }
        return teachingClass;
    }

    private String resolvePtaKeyword(String className, String ptaKeyword) {
        if (ptaKeyword != null && !ptaKeyword.isBlank()) {
            return ptaKeyword.trim();
        }
        return className == null ? null : className.trim();
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNotBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private Long findActiveStudentUserIdByUsername(String username) {
        String normalizedUsername = blankToNull(username);
        if (normalizedUsername == null) {
            return null;
        }
        UserEntity user = userRepo.findByUsername(normalizedUsername).orElse(null);
        if (user == null || user.getRole() != UserRole.STUDENT || !Boolean.TRUE.equals(user.getEnabled())) {
            return null;
        }
        return user.getId();
    }

    private Long resolveRequiredStudentUserId(String username) {
        String normalizedUsername = blankToNull(username);
        if (normalizedUsername == null) {
            throw new IllegalArgumentException("studentNum is required when adding a student manually");
        }
        UserEntity user = userRepo.findByUsername(normalizedUsername).orElse(null);
        if (user == null) {
            throw new IllegalArgumentException("学生账号不存在，不需要添加");
        }
        if (user.getRole() != UserRole.STUDENT) {
            throw new IllegalArgumentException("matched account is not a STUDENT role: " + normalizedUsername);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("student account is disabled: " + normalizedUsername);
        }
        return user.getId();
    }

    private void bindStudentAccountIfNeeded(ClassStudentEntity student, Long userId) {
        if (student == null || userId == null || student.getUserId() != null) {
            return;
        }
        student.setUserId(userId);
        studentRepo.save(student);
    }

    private String normalizeUsername(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("username cannot exceed 64 characters");
        }
        return normalized;
    }

    private String normalizeRequiredText(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException(fieldName + " cannot exceed 128 characters");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
