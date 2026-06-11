package com.tap.backend.repo;

import com.tap.backend.domain.animation.AnimationFrameEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AnimationFrameRepository extends JpaRepository<AnimationFrameEntity, Long> {
    List<AnimationFrameEntity> findAllByExplainIdOrderByFrameIndexAsc(Long explainId);
    Optional<AnimationFrameEntity> findByExplainIdAndId(Long explainId, Long id);
    @Transactional
    void deleteAllByExplainId(Long explainId);
}
