package com.tap.backend.repo;

import com.tap.backend.domain.grading.TeacherSignatureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeacherSignatureRepository extends JpaRepository<TeacherSignatureEntity, Long> {
    List<TeacherSignatureEntity> findAllByTeacherIdOrderByCreatedAtAsc(Long teacherId);
    Optional<TeacherSignatureEntity> findByTeacherIdAndSignature(Long teacherId, String signature);
    void deleteByTeacherIdAndId(Long teacherId, Long id);
    boolean existsByTeacherIdAndSignature(Long teacherId, String signature);
}
