package com.tap.backend.service;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.domain.rag.CourseSpaceClassEntity;
import com.tap.backend.domain.rag.CourseSpaceDocumentEntity;
import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.domain.upload.UploadFolderEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.rag.LuceneBm25Service;
import com.tap.backend.rag.MilvusSearchService;
import com.tap.backend.repo.ClassStudentRepository;
import com.tap.backend.repo.CourseSpaceClassRepository;
import com.tap.backend.repo.CourseSpaceDocumentRepository;
import com.tap.backend.repo.CourseSpaceRepository;
import com.tap.backend.repo.DocChunkRepository;
import com.tap.backend.repo.DocumentRepository;
import com.tap.backend.repo.TeachingClassRepository;
import com.tap.backend.repo.UploadFolderRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CourseSpaceService {

    private static final Logger log = LoggerFactory.getLogger(CourseSpaceService.class);
    private static final String RAG_TASKS_KEY = "rag:tasks";

    private final CourseSpaceRepository courseSpaceRepo;
    private final CourseSpaceClassRepository courseSpaceClassRepo;
    private final CourseSpaceDocumentRepository courseSpaceDocRepo;
    private final DocumentRepository documentRepo;
    private final DocumentIngestService documentIngestService;
    private final UploadFolderRepository uploadFolderRepo;
    private final TeachingClassRepository teachingClassRepo;
    private final ClassStudentRepository classStudentRepo;
    private final DocChunkRepository docChunkRepo;
    private final LuceneBm25Service luceneBm25Service;
    private final MilvusSearchService milvusSearchService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public record RagChatScope(Long activeClassId, String activeClassName, List<Long> boundClassIds) {}

    public CourseSpaceService(CourseSpaceRepository courseSpaceRepo,
                              CourseSpaceClassRepository courseSpaceClassRepo,
                              CourseSpaceDocumentRepository courseSpaceDocRepo,
                              DocumentRepository documentRepo,
                              DocumentIngestService documentIngestService,
                              UploadFolderRepository uploadFolderRepo,
                              TeachingClassRepository teachingClassRepo,
                              ClassStudentRepository classStudentRepo,
                              DocChunkRepository docChunkRepo,
                              LuceneBm25Service luceneBm25Service,
                              MilvusSearchService milvusSearchService,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.courseSpaceRepo = courseSpaceRepo;
        this.courseSpaceClassRepo = courseSpaceClassRepo;
        this.courseSpaceDocRepo = courseSpaceDocRepo;
        this.documentRepo = documentRepo;
        this.documentIngestService = documentIngestService;
        this.uploadFolderRepo = uploadFolderRepo;
        this.teachingClassRepo = teachingClassRepo;
        this.classStudentRepo = classStudentRepo;
        this.docChunkRepo = docChunkRepo;
        this.luceneBm25Service = luceneBm25Service;
        this.milvusSearchService = milvusSearchService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CourseSpaceEntity createSpace(UserEntity teacher, String name, String term,
                                         String courseName, String description,
                                         String defaultMode, Boolean allowWebSearch,
                                         Boolean requireCitation, String docVisibility,
                                         List<Long> classIds) {
        CourseSpaceEntity cs = new CourseSpaceEntity();
        cs.setTeacher(teacher);
        cs.setName(name);
        cs.setTerm(term);
        cs.setCourseName(courseName);
        cs.setDescription(description);
        if (defaultMode != null && !defaultMode.isBlank()) {
            cs.setDefaultMode(defaultMode);
        }
        if (allowWebSearch != null) {
            cs.setAllowWebSearch(allowWebSearch);
        }
        if (requireCitation != null) {
            cs.setRequireCitation(requireCitation);
        }
        if (docVisibility != null && !docVisibility.isBlank()) {
            cs.setDocVisibility(docVisibility);
        }
        CourseSpaceEntity saved = courseSpaceRepo.save(cs);
        replaceBoundClasses(saved, teacher.getId(), classIds);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CourseSpaceEntity> listSpaces(Long teacherId) {
        return courseSpaceRepo.findAllByTeacherId(teacherId);
    }

    @Transactional(readOnly = true)
    public List<CourseSpaceEntity> listPublicSpaces() {
        return courseSpaceRepo.findAllByDocVisibilityIgnoreCase("public");
    }

    @Transactional(readOnly = true)
    public List<CourseSpaceEntity> listReadableSpacesForStudent(String studentNum) {
        Set<Long> courseSpaceIds = new LinkedHashSet<>(
                courseSpaceRepo.findAllByDocVisibilityIgnoreCase("public").stream()
                        .map(CourseSpaceEntity::getId)
                        .toList());
        if (studentNum != null && !studentNum.isBlank()) {
            Set<Long> classIds = classStudentRepo.findAllByStudentNum(studentNum.trim()).stream()
                    .map(ClassStudentEntity::getClassId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!classIds.isEmpty()) {
                courseSpaceClassRepo.findAllByClassIdIn(classIds).stream()
                        .map(CourseSpaceClassEntity::getCourseSpaceId)
                        .forEach(courseSpaceIds::add);
            }
        }
        if (courseSpaceIds.isEmpty()) {
            return List.of();
        }
        return courseSpaceRepo.findAllById(courseSpaceIds).stream()
                .filter(cs -> isPubliclyReadable(cs) || isClassScoped(cs))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseSpaceEntity getSpace(Long id) {
        return courseSpaceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("course space not found"));
    }

    @Transactional(readOnly = true)
    public CourseSpaceEntity requireOwnedSpace(Long id, Long teacherId) {
        CourseSpaceEntity cs = getSpace(id);
        if (!cs.getTeacherId().equals(teacherId)) {
            throw new IllegalArgumentException("not your course space");
        }
        return cs;
    }

    @Transactional(readOnly = true)
    public CourseSpaceEntity requireReadableSpace(Long id, Long requesterUserId, boolean allowPublicRead) {
        CourseSpaceEntity cs = getSpace(id);
        if (requesterUserId != null && cs.getTeacherId().equals(requesterUserId)) {
            return cs;
        }
        if (allowPublicRead && isPubliclyReadable(cs)) {
            return cs;
        }
        throw new IllegalArgumentException("course space not accessible");
    }

    @Transactional(readOnly = true)
    public CourseSpaceEntity requireReadableSpaceForStudent(Long id, String studentNum) {
        CourseSpaceEntity cs = getSpace(id);
        if (isPubliclyReadable(cs)) {
            return cs;
        }
        if (!isClassScoped(cs) || studentNum == null || studentNum.isBlank()) {
            throw new IllegalArgumentException("course space not accessible");
        }
        Set<Long> classIds = classStudentRepo.findAllByStudentNum(studentNum.trim()).stream()
                .map(ClassStudentEntity::getClassId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) {
            throw new IllegalArgumentException("course space not accessible");
        }
        if (courseSpaceClassRepo.existsByCourseSpaceIdAndClassIdIn(id, classIds)) {
            return cs;
        }
        throw new IllegalArgumentException("course space not accessible");
    }

    public boolean isPubliclyReadable(CourseSpaceEntity cs) {
        if (cs == null || cs.getDocVisibility() == null) {
            return false;
        }
        return "public".equalsIgnoreCase(cs.getDocVisibility().trim());
    }

    public boolean isClassScoped(CourseSpaceEntity cs) {
        if (cs == null || cs.getDocVisibility() == null) {
            return false;
        }
        return "class".equalsIgnoreCase(cs.getDocVisibility().trim());
    }

    @Transactional(readOnly = true)
    public List<Long> listBoundClassIds(Long courseSpaceId) {
        return courseSpaceClassRepo.findAllByCourseSpaceId(courseSpaceId).stream()
                .map(CourseSpaceClassEntity::getClassId)
                .toList();
    }

    @Transactional(readOnly = true)
    public RagChatScope resolveTeacherRagScope(CourseSpaceEntity courseSpace, Long teacherId, Long requestedClassId) {
        if (courseSpace == null || !isClassScoped(courseSpace)) {
            return new RagChatScope(null, null, List.of());
        }
        List<Long> boundClassIds = listBoundClassIds(courseSpace.getId());
        if (boundClassIds.isEmpty()) {
            throw new IllegalArgumentException("当前课程空间未绑定班级，无法执行班级知识问答");
        }
        if (requestedClassId != null) {
            if (!boundClassIds.contains(requestedClassId)) {
                throw new IllegalArgumentException("当前课程空间未绑定所选班级，无法跨班使用");
            }
            TeachingClassEntity teachingClass = teachingClassRepo.findById(requestedClassId)
                    .orElseThrow(() -> new IllegalArgumentException("所选班级不存在"));
            if (teacherId != null && !teacherId.equals(teachingClass.getTeacherId())) {
                throw new IllegalArgumentException("所选班级不属于当前教师");
            }
            return new RagChatScope(teachingClass.getId(), teachingClass.getName(), boundClassIds);
        }
        if (boundClassIds.size() == 1) {
            TeachingClassEntity teachingClass = teachingClassRepo.findById(boundClassIds.get(0)).orElse(null);
            return new RagChatScope(
                    boundClassIds.get(0),
                    teachingClass == null ? null : teachingClass.getName(),
                    boundClassIds);
        }
        return new RagChatScope(null, null, boundClassIds);
    }

    @Transactional(readOnly = true)
    public RagChatScope resolveStudentRagScope(CourseSpaceEntity courseSpace, String studentNum, Long requestedClassId) {
        if (courseSpace == null || !isClassScoped(courseSpace)) {
            return new RagChatScope(null, null, List.of());
        }
        if (studentNum == null || studentNum.isBlank()) {
            throw new IllegalArgumentException("学生身份无效，无法访问班级知识空间");
        }

        Set<Long> studentClassIds = classStudentRepo.findAllByStudentNum(studentNum.trim()).stream()
                .map(ClassStudentEntity::getClassId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (studentClassIds.isEmpty()) {
            throw new IllegalArgumentException("当前学生未绑定班级，无法访问班级知识空间");
        }

        List<Long> accessibleBoundClassIds = listBoundClassIds(courseSpace.getId()).stream()
                .filter(studentClassIds::contains)
                .toList();
        if (accessibleBoundClassIds.isEmpty()) {
            throw new IllegalArgumentException("当前课程空间未向该学生所在班级开放");
        }

        if (requestedClassId != null) {
            if (!accessibleBoundClassIds.contains(requestedClassId)) {
                throw new IllegalArgumentException("当前课程空间未向所选班级开放");
            }
            TeachingClassEntity teachingClass = teachingClassRepo.findById(requestedClassId).orElse(null);
            return new RagChatScope(
                    requestedClassId,
                    teachingClass == null ? null : teachingClass.getName(),
                    accessibleBoundClassIds);
        }
        if (accessibleBoundClassIds.size() == 1) {
            TeachingClassEntity teachingClass = teachingClassRepo.findById(accessibleBoundClassIds.get(0)).orElse(null);
            return new RagChatScope(
                    accessibleBoundClassIds.get(0),
                    teachingClass == null ? null : teachingClass.getName(),
                    accessibleBoundClassIds);
        }
        return new RagChatScope(null, null, accessibleBoundClassIds);
    }

    @Transactional
    public CourseSpaceEntity updateSpace(Long id, Long teacherId, String name, String term,
                                          String courseName, String description,
                                          String defaultMode, Boolean allowWebSearch,
                                          Boolean requireCitation, String docVisibility,
                                          List<Long> classIds) {
        CourseSpaceEntity cs = courseSpaceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("course space not found"));
        if (!cs.getTeacherId().equals(teacherId)) {
            throw new IllegalArgumentException("not your course space");
        }
        if (name != null) cs.setName(name);
        if (term != null) cs.setTerm(term);
        if (courseName != null) cs.setCourseName(courseName);
        if (description != null) cs.setDescription(description);
        if (defaultMode != null) cs.setDefaultMode(defaultMode);
        if (allowWebSearch != null) cs.setAllowWebSearch(allowWebSearch);
        if (requireCitation != null) cs.setRequireCitation(requireCitation);
        if (docVisibility != null) cs.setDocVisibility(docVisibility);
        CourseSpaceEntity saved = courseSpaceRepo.save(cs);
        if (docVisibility != null && !"class".equalsIgnoreCase(docVisibility)) {
            courseSpaceClassRepo.deleteAllByCourseSpaceId(saved.getId());
        } else if (classIds != null) {
            replaceBoundClasses(saved, teacherId, classIds);
        }
        return saved;
    }

    @Transactional
    public void deleteSpace(Long id, Long teacherId) {
        CourseSpaceEntity cs = courseSpaceRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("course space not found"));
        if (!cs.getTeacherId().equals(teacherId)) {
            throw new IllegalArgumentException("not your course space");
        }

        luceneBm25Service.deleteByCourseSpace(id);
        try {
            milvusSearchService.deleteByCourseSpace(id);
        } catch (Exception e) {
            log.warn("Failed to clear Milvus rows for courseSpaceId={}: {}", id, e.getMessage());
        }

        var chunks = docChunkRepo.findAllByCourseSpaceId(id);
        if (!chunks.isEmpty()) {
            docChunkRepo.deleteAllInBatch(chunks);
        }

        var docs = courseSpaceDocRepo.findAllByCourseSpaceId(id);
        if (!docs.isEmpty()) {
            courseSpaceDocRepo.deleteAllInBatch(docs);
        }

        courseSpaceClassRepo.deleteAllByCourseSpaceId(id);
        courseSpaceRepo.delete(cs);
    }

    @Transactional
    public CourseSpaceDocumentEntity uploadDocument(Long courseSpaceId, UserEntity teacher,
                                                     MultipartFile file, String docType) throws Exception {
        CourseSpaceEntity cs = courseSpaceRepo.findById(courseSpaceId)
                .orElseThrow(() -> new IllegalArgumentException("course space not found"));
        if (!cs.getTeacherId().equals(teacher.getId())) {
            throw new IllegalArgumentException("not your course space");
        }

        // Create or reuse an upload folder for this course space
        String folderName = "rag-cs-" + courseSpaceId;
        UploadFolderEntity folder = uploadFolderRepo.findAllByUser_Id(teacher.getId()).stream()
                .filter(f -> folderName.equals(f.getFolderName()))
                .findFirst()
                .orElseGet(() -> {
                    UploadFolderEntity nf = new UploadFolderEntity();
                    nf.setUser(teacher);
                    nf.setFolderName(folderName);
                    return uploadFolderRepo.save(nf);
                });

        // Ingest the file using existing DocumentIngestService
        String originalFilename = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        List<DocumentIngestService.StoredDocument> stored =
                documentIngestService.ingestMultipartFiles(teacher, folder, List.of(file), List.of(originalFilename));

        if (stored.isEmpty()) {
            throw new IllegalArgumentException("file is empty or could not be ingested");
        }

        DocumentIngestService.StoredDocument sd = stored.get(0);

        // Load the persisted DocumentEntity
        DocumentEntity docEntity = documentRepo.findById(sd.id())
                .orElseThrow(() -> new IllegalStateException("ingested document not found"));

        // Create course_space_document record
        CourseSpaceDocumentEntity csDoc = new CourseSpaceDocumentEntity();
        csDoc.setCourseSpace(cs);
        csDoc.setDocument(docEntity);
        csDoc.setDocType(docType == null ? "textbook" : docType);
        csDoc.setStatus("PENDING");
        csDoc.setChunkCount(0);
        csDoc = courseSpaceDocRepo.save(csDoc);

        log.info("Uploaded document {} to course space {}, csDocId={}", sd.id(), courseSpaceId, csDoc.getId());
        return csDoc;
    }

    @Transactional(readOnly = true)
    public List<CourseSpaceDocumentEntity> listDocuments(Long courseSpaceId) {
        return courseSpaceDocRepo.findAllByCourseSpaceId(courseSpaceId);
    }

    @Transactional
    public CourseSpaceDocumentEntity queueDocumentReprocess(Long courseSpaceId, Long courseSpaceDocId, Long teacherId) {
        requireOwnedSpace(courseSpaceId, teacherId);
        CourseSpaceDocumentEntity csDoc = courseSpaceDocRepo.findById(courseSpaceDocId)
                .orElseThrow(() -> new IllegalArgumentException("course space document not found"));
        if (!courseSpaceId.equals(csDoc.getCourseSpaceId())) {
            throw new IllegalArgumentException("document does not belong to current course space");
        }
        if ("PROCESSING".equalsIgnoreCase(csDoc.getStatus())) {
            return csDoc;
        }
        csDoc.setStatus("PENDING");
        csDoc.setErrorMessage(null);
        return courseSpaceDocRepo.save(csDoc);
    }

    @Transactional
    public List<CourseSpaceDocumentEntity> queueAllDocumentsForReprocess(Long courseSpaceId, Long teacherId) {
        requireOwnedSpace(courseSpaceId, teacherId);
        List<CourseSpaceDocumentEntity> docs = courseSpaceDocRepo.findAllByCourseSpaceId(courseSpaceId);
        List<CourseSpaceDocumentEntity> queued = new java.util.ArrayList<>();
        for (CourseSpaceDocumentEntity doc : docs) {
            if ("PROCESSING".equalsIgnoreCase(doc.getStatus())) {
                continue;
            }
            doc.setStatus("PENDING");
            doc.setErrorMessage(null);
            queued.add(doc);
        }
        if (!queued.isEmpty()) {
            courseSpaceDocRepo.saveAll(queued);
        }
        return queued;
    }

    @Transactional(readOnly = true)
    public long countDocuments(Long courseSpaceId) {
        return courseSpaceDocRepo.countByCourseSpaceId(courseSpaceId);
    }

    @Transactional(readOnly = true)
    public void rebuildBm25Index(Long courseSpaceId, Long teacherId) {
        requireOwnedSpace(courseSpaceId, teacherId);
        luceneBm25Service.rebuildCourseSpace(courseSpaceId);
    }

    private void pushRagTask(Long courseSpaceDocId, Long courseSpaceId, long documentId) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "courseSpaceDocId", courseSpaceDocId,
                    "courseSpaceId", courseSpaceId,
                    "documentId", documentId
            ));
            redisTemplate.opsForList().leftPush(RAG_TASKS_KEY, json);
            log.info("Pushed rag task: {}", json);
        } catch (Exception e) {
            log.error("Failed to push rag task to Redis", e);
        }
    }

    private void replaceBoundClasses(CourseSpaceEntity courseSpace, Long teacherId, List<Long> classIds) {
        courseSpaceClassRepo.deleteAllByCourseSpaceId(courseSpace.getId());
        if (classIds == null || classIds.isEmpty()) {
            return;
        }
        List<TeachingClassEntity> classes = teachingClassRepo.findAllById(classIds);
        if (classes.size() != new LinkedHashSet<>(classIds).size()) {
            throw new IllegalArgumentException("some teaching classes were not found");
        }
        for (TeachingClassEntity teachingClass : classes) {
            if (!teacherId.equals(teachingClass.getTeacherId())) {
                throw new IllegalArgumentException("class does not belong to current teacher");
            }
            CourseSpaceClassEntity binding = new CourseSpaceClassEntity();
            binding.setCourseSpace(courseSpace);
            binding.setTeachingClass(teachingClass);
            courseSpaceClassRepo.save(binding);
        }
    }
}
