package com.tap.backend.repo;

import com.tap.backend.domain.animation.AnimationExplainEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimationExplainRepository extends JpaRepository<AnimationExplainEntity, Long> {
    List<AnimationExplainEntity> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
