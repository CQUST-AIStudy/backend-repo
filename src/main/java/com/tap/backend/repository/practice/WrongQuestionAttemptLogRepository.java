package com.tap.backend.repository.practice;

import com.tap.backend.domain.practice.WrongQuestionAttemptLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WrongQuestionAttemptLogRepository
    extends JpaRepository<WrongQuestionAttemptLogEntity, Long> {

  Page<WrongQuestionAttemptLogEntity> findByNotebookIdOrderByAttemptAtDesc(
      Long notebookId, Pageable pageable);
}
