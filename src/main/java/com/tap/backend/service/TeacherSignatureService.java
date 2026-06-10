package com.tap.backend.service;

import com.tap.backend.domain.grading.TeacherSignatureEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.TeacherSignatureRepository;
import com.tap.backend.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TeacherSignatureService {

    private final TeacherSignatureRepository signatureRepo;
    private final UserRepository userRepo;

    public TeacherSignatureService(TeacherSignatureRepository signatureRepo,
                                   UserRepository userRepo) {
        this.signatureRepo = signatureRepo;
        this.userRepo = userRepo;
    }

    @Transactional(readOnly = true)
    public List<TeacherSignatureEntity> listByTeacher(Long teacherId) {
        return signatureRepo.findAllByTeacherIdOrderByCreatedAtAsc(teacherId);
    }

    @Transactional
    public TeacherSignatureEntity add(Long teacherId, String signature) {
        String normalized = signature.trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("Signature must be 1-64 characters");
        }
        if (signatureRepo.existsByTeacherIdAndSignature(teacherId, normalized)) {
            throw new IllegalArgumentException("Signature already exists");
        }
        UserEntity teacher = userRepo.findById(teacherId)
                .orElseThrow(() -> new IllegalArgumentException("Teacher not found"));
        TeacherSignatureEntity entity = new TeacherSignatureEntity();
        entity.setTeacher(teacher);
        entity.setSignature(normalized);
        return signatureRepo.save(entity);
    }

    @Transactional
    public void remove(Long teacherId, Long signatureId) {
        signatureRepo.deleteByTeacherIdAndId(teacherId, signatureId);
    }

    /**
     * Auto-save a signature used in a grading task (idempotent).
     */
    @Transactional
    public void ensureExists(Long teacherId, String signature) {
        if (signature == null || signature.isBlank()) return;
        String normalized = signature.trim();
        if (!signatureRepo.existsByTeacherIdAndSignature(teacherId, normalized)) {
            add(teacherId, normalized);
        }
    }
}
