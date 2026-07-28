package com.tap.backend.repo;

import com.tap.backend.domain.animation.StudentCodeDemoEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentCodeDemoRepository extends JpaRepository<StudentCodeDemoEntity, Long> {
    Optional<StudentCodeDemoEntity> findByStudentProfileIdAndOfferingIdAndProblemNo(
            Long studentProfileId, Long offeringId, String problemNo);
}
