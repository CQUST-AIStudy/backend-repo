package com.tap.backend.service;

import com.tap.backend.domain.grading.*;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.GradingRubricRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class RubricService {

    private final GradingRubricRepository rubricRepo;
    private final GradingTaskRepository taskRepo;
    private final UserRepository userRepo;

    public RubricService(GradingRubricRepository rubricRepo,
                         GradingTaskRepository taskRepo,
                         UserRepository userRepo) {
        this.rubricRepo = rubricRepo;
        this.taskRepo = taskRepo;
        this.userRepo = userRepo;
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
}
