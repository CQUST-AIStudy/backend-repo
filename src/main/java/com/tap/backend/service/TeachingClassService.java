package com.tap.backend.service;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.ClassStudentRepository;
import com.tap.backend.repo.TeachingClassRepository;
import com.tap.backend.repo.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    public TeachingClassService(
            TeachingClassRepository classRepo,
            ClassStudentRepository studentRepo,
            UserRepository userRepo,
            PasswordEncoder passwordEncoder,
            LegacyPtaRosterService legacyPtaRosterService
    ) {
        this.classRepo = classRepo;
        this.studentRepo = studentRepo;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.legacyPtaRosterService = legacyPtaRosterService;
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
        return classRepo.findAllByTeacherIdAndStatus(teacherId, "ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<TeachingClassEntity> listAll() {
        return classRepo.findAll();
    }

    @Transactional(readOnly = true)
    public List<TeachingClassEntity> listActiveClasses() {
        return classRepo.findAllByStatus("ACTIVE");
    }

    @Transactional
    public TeachingClassEntity createClass(
            UserEntity teacher,
            String name,
            String classCode,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {
        String normalizedName = normalizeRequiredText(name, "name");
        String resolvedClassCode = resolveCreateClassCode(classCode, normalizedName);
        if (classRepo.existsByClassCode(resolvedClassCode)) {
            throw new IllegalArgumentException("class code already exists: " + resolvedClassCode);
        }
        TeachingClassEntity teachingClass = new TeachingClassEntity();
        teachingClass.setTeacher(teacher);
        teachingClass.setName(normalizedName);
        teachingClass.setClassCode(resolvedClassCode);
        teachingClass.setJoinPassword(resolveCreateJoinPassword(joinPassword));
        teachingClass.setGrade(grade);
        teachingClass.setCourseName(courseName);
        teachingClass.setDescription(description);
        String resolvedGroupName = normalizeNullableText(firstNotBlank(ptaGroupName, ptaKeyword));
        teachingClass.setPtaGroupName(resolvedGroupName);
        teachingClass.setPtaKeyword(resolvePtaKeyword(name, firstNotBlank(resolvedGroupName, ptaKeyword)));
        if (syncEnabled != null) {
            teachingClass.setSyncEnabled(syncEnabled);
        }
        teachingClass = classRepo.save(teachingClass);
        // Initialize lazy teacher proxy before leaving the transaction.
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
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {
        TeachingClassEntity teachingClass = requireOwnedClass(classId, teacherId);
        applyClassUpdates(teachingClass, name, joinPassword, grade, courseName, description, ptaKeyword, ptaGroupName, syncEnabled);
        teachingClass = classRepo.save(teachingClass);
        // Initialize lazy teacher proxy before leaving the transaction.
        teachingClass.getTeacher().getDisplayName();
        return teachingClass;
    }

    @Transactional
    public void deleteClass(Long classId, Long teacherId) {
        archiveClass(requireOwnedClass(classId, teacherId));
    }

    @Transactional
    public TeachingClassEntity updateClassAsAdmin(
            Long classId,
            Long teacherId,
            String name,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        if (teacherId != null) {
            teachingClass.setTeacher(requireTeacherUser(teacherId));
        }
        applyClassUpdates(teachingClass, name, joinPassword, grade, courseName, description, ptaKeyword, ptaGroupName, syncEnabled);
        teachingClass = classRepo.save(teachingClass);
        teachingClass.getTeacher().getDisplayName();
        return teachingClass;
    }

    @Transactional
    public void deleteClassAsAdmin(Long classId) {
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        archiveClass(teachingClass);
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
        return studentRepo.countByClassId(classId);
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
        return classRepo.findAllByIdInAndStatus(classIds, "ACTIVE");
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
        return classRepo.findAllByIdInAndStatus(classIds, "ACTIVE");
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

    private void archiveClass(TeachingClassEntity teachingClass) {
        teachingClass.setStatus("ARCHIVED");
        teachingClass.setArchivedAt(Instant.now());
        teachingClass.setSyncEnabled(false);
        if ("RUNNING".equals(teachingClass.getSyncStatus())) {
            teachingClass.setSyncStatus("IDLE");
        }
        classRepo.save(teachingClass);
    }

    private UserEntity requireTeacherUser(Long teacherId) {
        UserEntity teacher = userRepo.findById(teacherId)
                .orElseThrow(() -> new NoSuchElementException("teacher not found"));
        if (teacher.getRole() != UserRole.TEACHER && teacher.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("selected user is not a teacher");
        }
        if (!Boolean.TRUE.equals(teacher.getEnabled())) {
            throw new IllegalArgumentException("selected teacher is disabled");
        }
        return teacher;
    }

    private void applyClassUpdates(
            TeachingClassEntity teachingClass,
            String name,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {
        if (name != null) {
            teachingClass.setName(normalizeRequiredText(name, "name"));
        }
        if (joinPassword != null) {
            teachingClass.setJoinPassword(resolveCreateJoinPassword(joinPassword));
        }
        if (grade != null) {
            teachingClass.setGrade(grade);
        }
        if (courseName != null) {
            teachingClass.setCourseName(courseName);
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
    }

    private TeachingClassEntity requireOwnedClass(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = classRepo.findById(classId)
                .orElseThrow(() -> new NoSuchElementException("class not found"));
        if (!teacherId.equals(teachingClass.getTeacherId())) {
            throw new SecurityException("forbidden");
        }
        return teachingClass;
    }

    private String resolveCreateClassCode(String classCode, String name) {
        String normalized = blankToNull(classCode);
        if (normalized != null) {
            return normalized;
        }
        String prefix = String.valueOf(name == null ? "CLASS" : name)
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        if (prefix.length() > 8) {
            prefix = prefix.substring(0, 8);
        }
        if (prefix.isBlank()) {
            prefix = "CLASS";
        }
        for (int i = 0; i < 20; i++) {
            String candidate = prefix + Long.toString(System.currentTimeMillis() + i, 36).toUpperCase(Locale.ROOT);
            if (candidate.length() > 32) {
                candidate = candidate.substring(0, 32);
            }
            if (!classRepo.existsByClassCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("failed to generate class code");
    }

    private String resolveCreateJoinPassword(String joinPassword) {
        String normalized = blankToNull(joinPassword);
        return normalized == null ? "123456" : normalized;
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
            throw new IllegalArgumentException("student account does not exist: " + normalizedUsername);
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
