package com.tap.backend.service;

import com.tap.backend.domain.grading.*;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.GradingRubricRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RubricService {

    private final GradingRubricRepository rubricRepo;
    private final GradingTaskRepository taskRepo;
    private final UserRepository userRepo;
    private final GradingWorkerClient workerClient;
    private final ObjectStorageService objectStorage;

    public RubricService(GradingRubricRepository rubricRepo,
                         GradingTaskRepository taskRepo,
                         UserRepository userRepo,
                         GradingWorkerClient workerClient,
                         ObjectStorageService objectStorage) {
        this.rubricRepo = rubricRepo;
        this.taskRepo = taskRepo;
        this.userRepo = userRepo;
        this.workerClient = workerClient;
        this.objectStorage = objectStorage;
    }

    @Transactional
    public GradingRubricEntity create(Long teacherId, String name, String subject,
                                       String description, String customPrompt,
                                       List<DimensionInput> dimensions) {
        validateDimensions(dimensions);

        UserEntity teacher = userRepo.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Teacher not found"));

        GradingRubricEntity rubric = new GradingRubricEntity();
        rubric.setTeacher(teacher);
        rubric.setName(name);
        rubric.setSubject(subject);
        rubric.setDescription(description);
        rubric.setCustomPrompt(customPrompt);

        for (int i = 0; i < dimensions.size(); i++) {
            DimensionInput d = dimensions.get(i);
            RubricDimensionEntity dim = new RubricDimensionEntity();
            dim.setRubric(rubric);
            dim.setName(d.name());
            dim.setDescription(d.description());
            dim.setMaxScore(d.maxScore());
            dim.setWeight(d.weight());
            dim.setSortOrder(i);
            rubric.getDimensions().add(dim);
        }

        return rubricRepo.save(rubric);
    }

    @Transactional
    public GradingRubricEntity createFromImage(Long teacherId, MultipartFile image,
                                                String nameHint, String subjectHint,
                                                String descriptionHint, String customPromptHint) {
        GradingWorkerClient.ParseRubricResult parsed = workerClient.parseRubricImage(image);
        if (parsed.getDimensions().isEmpty()) {
            throw new IllegalArgumentException("Could not parse any scoring dimensions from the image");
        }

        List<DimensionInput> inputs = new ArrayList<>();
        for (GradingWorkerClient.ParseRubricResult.Dimension d : parsed.getDimensions()) {
            if (d.getName() == null || d.getName().isBlank() || d.getMaxScore() == null) {
                continue;
            }
            inputs.add(new DimensionInput(d.getName(), d.getDescription(), d.getMaxScore(), d.getWeight()));
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Parsed dimensions are invalid");
        }
        validateDimensions(inputs);

        // Upload image to object storage
        String ext = resolveImageExtension(image.getOriginalFilename());
        String objectKey = "rubrics/" + teacherId + "/" + UUID.randomUUID() + ext;
        try {
            objectStorage.putBytes(objectKey, image.getBytes(), image.getContentType() != null ? image.getContentType() : "image/png");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to upload rubric image", e);
        }

        String rubricName = firstNonBlank(nameHint, parsed.getRubricName(), "实验报告评分标准");
        String subject = firstNonBlank(subjectHint, "实验课程");
        String description = firstNonBlank(descriptionHint,
                "由评分表图片自动解析生成的量规。原始总分：" + parsed.getTotalScore() + "。", "");

        UserEntity teacher = userRepo.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Teacher not found"));

        GradingRubricEntity rubric = new GradingRubricEntity();
        rubric.setTeacher(teacher);
        rubric.setName(rubricName);
        rubric.setSubject(subject);
        rubric.setDescription(description);
        rubric.setCustomPrompt(customPromptHint);
        rubric.setImageObjectKey(objectKey);
        rubric.setImageParsedAt(Instant.now());
        rubric.setImageParsedJson(parsed.getRawJson());

        for (int i = 0; i < inputs.size(); i++) {
            DimensionInput d = inputs.get(i);
            RubricDimensionEntity dim = new RubricDimensionEntity();
            dim.setRubric(rubric);
            dim.setName(d.name());
            dim.setDescription(d.description());
            dim.setMaxScore(d.maxScore());
            dim.setWeight(d.weight());
            dim.setSortOrder(i);
            rubric.getDimensions().add(dim);
        }

        return rubricRepo.save(rubric);
    }

    @Transactional
    public GradingRubricEntity update(Long rubricId, Long teacherId, String name, String subject,
                                       String description, String customPrompt,
                                       List<DimensionInput> dimensions) {
        GradingRubricEntity rubric = requireOwnedRubric(rubricId, teacherId);

        if (taskRepo.existsByRubricIdAndStatus(rubricId, GradingTaskStatus.PROCESSING)) {
            throw new IllegalStateException("Rubric is referenced by active grading tasks");
        }

        validateDimensions(dimensions);

        rubric.setName(name);
        rubric.setSubject(subject);
        rubric.setDescription(description);
        rubric.setCustomPrompt(customPrompt);

        rubric.getDimensions().clear();
        for (int i = 0; i < dimensions.size(); i++) {
            DimensionInput d = dimensions.get(i);
            RubricDimensionEntity dim = new RubricDimensionEntity();
            dim.setRubric(rubric);
            dim.setName(d.name());
            dim.setDescription(d.description());
            dim.setMaxScore(d.maxScore());
            dim.setWeight(d.weight());
            dim.setSortOrder(i);
            rubric.getDimensions().add(dim);
        }

        return rubricRepo.save(rubric);
    }

    @Transactional(readOnly = true)
    public List<GradingRubricEntity> listByTeacher(Long teacherId, String subject) {
        List<GradingRubricEntity> rubrics;
        if (subject != null && !subject.isBlank()) {
            rubrics = rubricRepo.findAllByTeacherIdAndSubject(teacherId, subject);
        } else {
            rubrics = rubricRepo.findAllByTeacherId(teacherId);
        }
        // Force-load dimensions to avoid LazyInitializationException in controller
        rubrics.forEach(r -> r.getDimensions().size());
        return rubrics;
    }

    @Transactional(readOnly = true)
    public GradingRubricEntity getDetail(Long rubricId, Long teacherId) {
        GradingRubricEntity rubric = requireOwnedRubric(rubricId, teacherId);
        // force load dimensions
        rubric.getDimensions().size();
        return rubric;
    }

    private GradingRubricEntity requireOwnedRubric(Long rubricId, Long teacherId) {
        GradingRubricEntity rubric = rubricRepo.findById(rubricId)
                .orElseThrow(() -> new IllegalArgumentException("Rubric not found"));
        if (!rubric.getTeacherId().equals(teacherId)) {
            throw new IllegalArgumentException("Rubric not found");
        }
        return rubric;
    }

    private void validateDimensions(List<DimensionInput> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            throw new IllegalArgumentException("At least one dimension is required");
        }
        int weightSum = 0;
        for (DimensionInput d : dimensions) {
            if (d.name() == null || d.name().isBlank()) {
                throw new IllegalArgumentException("Dimension name must not be empty");
            }
            if (d.maxScore() == null || d.maxScore().signum() <= 0) {
                throw new IllegalArgumentException("Dimension max_score must be greater than zero");
            }
            if (d.weight() == null || d.weight() <= 0) {
                throw new IllegalArgumentException("Dimension weight must be greater than zero");
            }
            weightSum += d.weight();
        }
        if (weightSum != 100) {
            throw new IllegalArgumentException("Dimension weights must sum to 100, got " + weightSum);
        }
    }

    public record DimensionInput(String name, String description,
                                  java.math.BigDecimal maxScore, Integer weight) {}

    private static String resolveImageExtension(String filename) {
        if (filename == null) {
            return ".png";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".gif")) return ".gif";
        if (lower.endsWith(".webp")) return ".webp";
        return ".png";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
