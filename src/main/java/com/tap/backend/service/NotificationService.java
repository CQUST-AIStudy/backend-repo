package com.tap.backend.service;

import com.tap.backend.domain.notification.NotificationEntity;
import com.tap.backend.repo.NotificationRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student-facing in-app notifications. Notifications are addressed by {@code student_no} so they
 * line up with the published-grade read path, and read/unread state is tracked via {@code read_at}.
 */
@Service
public class NotificationService {

    private static final int DEFAULT_LIMIT = 50;

    private final NotificationRepository notificationRepo;

    public NotificationService(NotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    /**
     * Records a "grade published" notification for the student. Never throws into the publish flow:
     * a blank student number is a no-op.
     */
    @Transactional
    public void createGradePublished(String studentNo, Long linkExperimentId, String title, String content) {
        if (studentNo == null || studentNo.isBlank()) {
            return;
        }
        NotificationEntity notification = new NotificationEntity();
        notification.setRecipientStudentNo(studentNo.trim());
        notification.setType(NotificationEntity.TYPE_GRADE_PUBLISHED);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setLinkExperimentId(linkExperimentId);
        notificationRepo.save(notification);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForStudent(String studentNo, int limit) {
        if (studentNo == null || studentNo.isBlank()) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 200);
        return notificationRepo
                .findByRecipientStudentNoOrderByCreatedAtDesc(studentNo, PageRequest.of(0, effectiveLimit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            return 0L;
        }
        return notificationRepo.countByRecipientStudentNoAndReadAtIsNull(studentNo);
    }

    @Transactional
    public int markRead(String studentNo, Collection<Long> ids) {
        if (studentNo == null || studentNo.isBlank() || ids == null || ids.isEmpty()) {
            return 0;
        }
        return notificationRepo.markReadByIds(studentNo, ids, Instant.now());
    }

    @Transactional
    public int markAllRead(String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            return 0;
        }
        return notificationRepo.markAllRead(studentNo, Instant.now());
    }

    private Map<String, Object> toDto(NotificationEntity notification) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", notification.getId());
        dto.put("type", notification.getType());
        dto.put("title", notification.getTitle());
        dto.put("content", notification.getContent());
        dto.put("linkExperimentId", notification.getLinkExperimentId());
        dto.put("read", notification.getReadAt() != null);
        dto.put("createdAt", notification.getCreatedAt() == null ? null : notification.getCreatedAt().toString());
        return dto;
    }
}
