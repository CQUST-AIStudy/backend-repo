package com.tap.backend.api.rag;

import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.service.CourseSpaceService;
import com.tap.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/student-rag")
public class StudentRagController {

    private final StudentSessionResolver studentSessionResolver;
    private final CourseSpaceService courseSpaceService;
    private final RagChatController ragChatController;

    public StudentRagController(StudentSessionResolver studentSessionResolver,
                                CourseSpaceService courseSpaceService,
                                RagChatController ragChatController) {
        this.studentSessionResolver = studentSessionResolver;
        this.courseSpaceService = courseSpaceService;
        this.ragChatController = ragChatController;
    }

    record StudentRagChatRequest(Long courseSpaceId, String query, String mode, Long classId, String className) {}

    @GetMapping("/course-spaces")
    public ApiResponse<List<Map<String, Object>>> listCourseSpaces(HttpServletRequest request) {
        String studentNum = studentSessionResolver.requireStudentId(request);
        List<CourseSpaceEntity> spaces = courseSpaceService.listReadableSpacesForStudent(studentNum);
        return ApiResponse.of(spaces.stream().map(this::spaceToMap).toList());
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(HttpServletRequest request,
                                                      @RequestBody StudentRagChatRequest chatRequest) {
        String studentId = studentSessionResolver.requireStudentId(request);
        return ragChatController.chatForReadableSpace(
                new RagChatController.RagChatRequest(
                        chatRequest.courseSpaceId(),
                        chatRequest.query(),
                        chatRequest.mode(),
                        chatRequest.classId(),
                        chatRequest.className()),
                null,
                true,
                studentId,
                studentId);
    }

    private Map<String, Object> spaceToMap(CourseSpaceEntity cs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cs.getId());
        map.put("name", cs.getName());
        map.put("term", cs.getTerm() == null ? "" : cs.getTerm());
        map.put("courseName", cs.getCourseName() == null ? "" : cs.getCourseName());
        map.put("description", cs.getDescription() == null ? "" : cs.getDescription());
        map.put("defaultMode", cs.getDefaultMode());
        map.put("allowWebSearch", cs.getAllowWebSearch());
        map.put("requireCitation", cs.getRequireCitation());
        map.put("docVisibility", cs.getDocVisibility());
        map.put("boundClassIds", courseSpaceService.listBoundClassIds(cs.getId()));
        map.put("createdAt", cs.getCreatedAt().toString());
        map.put("updatedAt", cs.getUpdatedAt().toString());
        return map;
    }
}
