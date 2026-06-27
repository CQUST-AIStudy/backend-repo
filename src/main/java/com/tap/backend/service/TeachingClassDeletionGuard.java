package com.tap.backend.service;

import com.tap.backend.repo.AssignmentOfferingReferenceRepository;
import org.springframework.stereotype.Component;

@Component
public class TeachingClassDeletionGuard {

    private final AssignmentOfferingReferenceRepository assignmentOfferingReferenceRepository;

    public TeachingClassDeletionGuard(AssignmentOfferingReferenceRepository assignmentOfferingReferenceRepository) {
        this.assignmentOfferingReferenceRepository = assignmentOfferingReferenceRepository;
    }

    public boolean hasBlockingReferences(Long classId) {
        return assignmentOfferingReferenceRepository.existsByClassId(classId);
    }
}
