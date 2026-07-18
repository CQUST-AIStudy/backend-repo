package com.tap.backend.api.classroom;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.TeachingClassService;
import com.tap.common.api.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/classes")
public class ClassroomController {

    private final TeachingClassService classService;
    private final UserRepository userRepo;
    private final TeacherPrincipalResolver teacherPrincipalResolver;
    private final PasswordEncoder passwordEncoder;

    public ClassroomController(
            TeachingClassService classService,
            UserRepository userRepo,
            TeacherPrincipalResolver teacherPrincipalResolver,
            PasswordEncoder passwordEncoder
    ) {
        this.classService = classService;
        this.userRepo = userRepo;
        this.teacherPrincipalResolver = teacherPrincipalResolver;
        this.passwordEncoder = passwordEncoder;
    }

    private UserEntity requireUser(UserPrincipal principal) {
        Long userId = teacherPrincipalResolver.requireTeacherId(principal);
        return userRepo.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("user not found"));
    }

    private Long optionalUserId(UserPrincipal principal) {
        if (principal == null) {
            return null;
        }
        return userRepo.findById(principal.userId())
                .orElseThrow(() -> new NoSuchElementException("user not found"))
                .getId();
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listClasses(@AuthenticationPrincipal UserPrincipal principal) {
        UserEntity user = requireUser(principal);
        List<TeachingClassEntity> classes = classService.listByTeacher(user.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (TeachingClassEntity teachingClass : classes) {
            result.add(toMap(teachingClass));
        }
        return ApiResponse.of(result);
    }

    record CreateClassRequest(
            String name,
            String classCode,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {}

    @PostMapping
    public ApiResponse<Map<String, Object>> createClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateClassRequest req
    ) {
        UserEntity user = requireUser(principal);
        TeachingClassEntity teachingClass = classService.createClass(
                user,
                req.name(),
                req.classCode(),
                req.joinPassword(),
                req.grade(),
                req.courseName(),
                req.description(),
                req.ptaKeyword(),
                req.ptaGroupName(),
                req.syncEnabled()
        );
        return ApiResponse.of(toMap(teachingClass));
    }

    record UpdateClassRequest(
            String name,
            String joinPassword,
            String grade,
            String courseName,
            String description,
            String ptaKeyword,
            String ptaGroupName,
            Boolean syncEnabled
    ) {}

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody UpdateClassRequest req
    ) {
        UserEntity user = requireUser(principal);
        TeachingClassEntity teachingClass = classService.updateClass(
                id,
                user.getId(),
                req.name(),
                req.joinPassword(),
                req.grade(),
                req.courseName(),
                req.description(),
                req.ptaKeyword(),
                req.ptaGroupName(),
                req.syncEnabled()
        );
        return ApiResponse.of(toMap(teachingClass));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        UserEntity user = requireUser(principal);
        classService.deleteClass(id, user.getId(), force);
        return ApiResponse.of(null);
    }

    @GetMapping("/{id}/students")
    public ApiResponse<List<Map<String, Object>>> listStudents(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        UserEntity user = requireUser(principal);
        List<ClassStudentEntity> students = classService.listStudentsForTeacher(id, user.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ClassStudentEntity student : students) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", student.getId());
            row.put("studentName", student.getStudentName());
            row.put("studentNum", student.getStudentNum());
            row.put("userId", student.getUserId());
            row.put("joinedAt", student.getJoinedAt());
            UserEntity tapUser = findTapUserForStudent(student);
            row.put("username", tapUser != null ? tapUser.getUsername() : null);
            row.put("hasPassword", tapUser != null && tapUser.getPasswordHash() != null && !tapUser.getPasswordHash().isBlank());
            result.add(row);
        }
        return ApiResponse.of(result);
    }

    private UserEntity findTapUserForStudent(ClassStudentEntity student) {
        if (student.getUserId() != null) {
            UserEntity byId = userRepo.findById(student.getUserId()).orElse(null);
            if (byId != null) {
                return byId;
            }
        }
        if (student.getStudentNum() != null && !student.getStudentNum().isBlank()) {
            return userRepo.findByUsernum(student.getStudentNum()).orElse(null);
        }
        return null;
    }

    record ResetStudentPasswordRequest(String newPassword) {}

    @PostMapping("/{classId}/students/{studentId}/reset-password")
    public ApiResponse<Void> resetStudentPassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId,
            @PathVariable Long studentId,
            @RequestBody ResetStudentPasswordRequest req
    ) {
        UserEntity user = requireUser(principal);
        if (req == null || req.newPassword() == null || req.newPassword().isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (req.newPassword().length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于6位");
        }
        ClassStudentEntity student = classService.getStudentForTeacher(classId, studentId, user.getId());
        UserEntity tapUser = findTapUserForStudent(student);
        if (tapUser == null) {
            throw new IllegalStateException("该学生尚未创建登录账号，无法重置密码");
        }
        tapUser.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepo.save(tapUser);
        return ApiResponse.of(null);
    }

    record AddStudentRequest(String studentName, String studentNum) {}

    @PostMapping("/{id}/students")
    public ApiResponse<Map<String, Object>> addStudent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody AddStudentRequest req
    ) {
        UserEntity user = requireUser(principal);
        ClassStudentEntity student = classService.addStudentForTeacher(
                id,
                user.getId(),
                req.studentName(),
                req.studentNum(),
                null
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", student.getId());
        result.put("classId", resolveClassId(student));
        result.put("studentName", student.getStudentName());
        result.put("studentNum", student.getStudentNum());
        result.put("userId", student.getUserId());
        result.put("joinedAt", student.getJoinedAt());
        return ApiResponse.of(result);
    }

    record ImportStudentAccountRequest(
            List<TeachingClassService.StudentAccountImportItem> students,
            String defaultPassword
    ) {}

    @PostMapping("/{id}/students/import-accounts")
    public ApiResponse<TeachingClassService.StudentAccountImportResult> importStudentAccounts(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody ImportStudentAccountRequest req
    ) {
        UserEntity user = requireUser(principal);
        return ApiResponse.of(classService.importStudentAccountsForTeacher(
                id,
                user.getId(),
                req == null ? List.of() : req.students(),
                req == null ? null : req.defaultPassword()
        ));
    }

    @PostMapping(value = "/{id}/students/import-accounts/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TeachingClassService.StudentAccountImportResult> importStudentAccountsFromExcel(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "defaultPassword", required = false) String defaultPassword
    ) throws Exception {
        UserEntity user = requireUser(principal);
        return ApiResponse.of(classService.importStudentAccountsForTeacher(
                id,
                user.getId(),
                parseStudentAccountExcel(file),
                defaultPassword
        ));
    }

    @DeleteMapping("/{classId}/students/{studentId}")
    public ApiResponse<Void> removeStudent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long classId,
            @PathVariable Long studentId
    ) {
        UserEntity user = requireUser(principal);
        classService.removeStudentForTeacher(classId, studentId, user.getId());
        return ApiResponse.of(null);
    }

    private List<TeachingClassService.StudentAccountImportItem> parseStudentAccountExcel(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("import file is required");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("import file has no sheet");
            }
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("import file has no header row");
            }
            Map<String, Integer> columns = resolveImportColumns(header, formatter);
            List<TeachingClassService.StudentAccountImportItem> items = new ArrayList<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String username = readCell(row, columns.get("username"), formatter);
                String studentName = readCell(row, columns.get("studentName"), formatter);
                String studentNum = readCell(row, columns.get("studentNum"), formatter);
                String password = readCell(row, columns.get("password"), formatter);
                if (isBlank(username) && isBlank(studentName) && isBlank(studentNum) && isBlank(password)) {
                    continue;
                }
                items.add(new TeachingClassService.StudentAccountImportItem(username, studentName, studentNum, password));
            }
            return items;
        }
    }

    private Map<String, Integer> resolveImportColumns(Row header, DataFormatter formatter) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim();
            String key = switch (value) {
                case "username", "账号", "登录账号", "用户名" -> "username";
                case "studentName", "姓名", "学生姓名" -> "studentName";
                case "studentNum", "学号", "学生学号" -> "studentNum";
                case "password", "密码", "初始密码" -> "password";
                default -> null;
            };
            if (key != null && !columns.containsKey(key)) {
                columns.put(key, cell.getColumnIndex());
            }
        }
        if (!columns.containsKey("username")) {
            throw new IllegalArgumentException("import file must contain username/账号 column");
        }
        if (!columns.containsKey("studentName")) {
            throw new IllegalArgumentException("import file must contain studentName/姓名 column");
        }
        return columns;
    }

    private String readCell(Row row, Integer index, DataFormatter formatter) {
        if (index == null) {
            return null;
        }
        Cell cell = row.getCell(index);
        return cell == null ? null : formatter.formatCellValue(cell).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record JoinClassRequest(String classCode, String password, String studentName, String studentNum) {}

    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> joinClass(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody JoinClassRequest req
    ) {
        ClassStudentEntity student = classService.joinClass(
                req.classCode(),
                req.password(),
                req.studentName(),
                req.studentNum(),
                optionalUserId(principal)
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", student.getId());
        result.put("classId", resolveClassId(student));
        result.put("studentName", student.getStudentName());
        result.put("studentNum", student.getStudentNum());
        result.put("userId", student.getUserId());
        result.put("joinedAt", student.getJoinedAt());
        return ApiResponse.of(result);
    }

    private Long resolveClassId(ClassStudentEntity student) {
        if (student.getClassId() != null) {
            return student.getClassId();
        }
        if (student.getTeachingClass() != null) {
            return student.getTeachingClass().getId();
        }
        return null;
    }

    private Map<String, Object> toMap(TeachingClassEntity teachingClass) {
        long studentCount = classService.countStudents(teachingClass.getId());
        String teacherName = "";
        if (teachingClass.getTeacher() != null) {
            UserEntity t = teachingClass.getTeacher();
            teacherName = t.getDisplayName() != null && !t.getDisplayName().isBlank()
                ? t.getDisplayName().trim()
                : (t.getUsername() != null ? t.getUsername().trim() : "");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", teachingClass.getId());
        result.put("name", teachingClass.getName());
        result.put("classCode", teachingClass.getClassCode());
        result.put("joinPassword", teachingClass.getJoinPassword());
        result.put("grade", teachingClass.getGrade());
        result.put("courseName", teachingClass.getCourseName());
        result.put("description", teachingClass.getDescription());
        result.put("teacherName", teacherName);
        result.put("studentCount", studentCount);
        result.put("ptaKeyword", teachingClass.getPtaKeyword());
        result.put("ptaProblemSetId", teachingClass.getPtaProblemSetId());
        result.put("ptaProblemSetName", teachingClass.getPtaProblemSetName());
        result.put("ptaGroupId", teachingClass.getPtaGroupId());
        result.put("ptaGroupName", teachingClass.getPtaGroupName());
        result.put("ptaBindingVerifiedAt", teachingClass.getPtaBindingVerifiedAt());
        result.put("ptaBindingVerifyStatus", teachingClass.getPtaBindingVerifyStatus());
        result.put("ptaBindingVerifyMessage", teachingClass.getPtaBindingVerifyMessage());
        result.put("syncEnabled", teachingClass.getSyncEnabled());
        result.put("lastSyncAt", teachingClass.getLastSyncAt());
        result.put("syncStatus", teachingClass.getSyncStatus());
        result.put("createdAt", teachingClass.getCreatedAt());
        result.put("updatedAt", teachingClass.getUpdatedAt());
        return result;
    }
}
