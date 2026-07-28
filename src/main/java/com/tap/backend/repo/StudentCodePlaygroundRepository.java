package com.tap.backend.repo;

import com.tap.backend.domain.animation.StudentCodePlaygroundEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentCodePlaygroundRepository extends JpaRepository<StudentCodePlaygroundEntity, Long> {

    List<StudentCodePlaygroundEntity> findTop50ByStudentNoOrderByCreatedAtDesc(String studentNo);

    Optional<StudentCodePlaygroundEntity> findByIdAndStudentNo(Long id, String studentNo);
}
