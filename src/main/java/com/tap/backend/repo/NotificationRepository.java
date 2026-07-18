package com.tap.backend.repo;

import com.tap.backend.domain.notification.NotificationEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    List<NotificationEntity> findByRecipientStudentNoOrderByCreatedAtDesc(String recipientStudentNo, Pageable pageable);

    long countByRecipientStudentNoAndReadAtIsNull(String recipientStudentNo);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.readAt = :readAt "
            + "WHERE n.recipientStudentNo = :studentNo AND n.readAt IS NULL AND n.id IN :ids")
    int markReadByIds(@Param("studentNo") String studentNo,
                      @Param("ids") Collection<Long> ids,
                      @Param("readAt") Instant readAt);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.readAt = :readAt "
            + "WHERE n.recipientStudentNo = :studentNo AND n.readAt IS NULL")
    int markAllRead(@Param("studentNo") String studentNo, @Param("readAt") Instant readAt);
}
