package com.tap.backend.repo;

import com.tap.backend.domain.classroom.ClassStudentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassStudentRepository extends JpaRepository<ClassStudentEntity, Long> {
    List<ClassStudentEntity> findAllByClassId(Long classId);
    boolean existsByClassIdAndStudentNum(Long classId, String studentNum);
    Optional<ClassStudentEntity> findByClassIdAndStudentNum(Long classId, String studentNum);
    List<ClassStudentEntity> findAllByUserId(Long userId);
    List<ClassStudentEntity> findAllByStudentNum(String studentNum);
}
