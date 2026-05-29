package com.tap.backend.academic.leetcode.recommendation;

import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for LeetCode recommendation requests.
 * Entries are automatically evicted after {@link #TTL_MINUTES} minutes
 * to prevent unbounded memory growth.
 */
@Component
public class LeetCodeRecommendationRequestStore {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeRecommendationRequestStore.class);

    /** Maximum age of a stored request before eviction. */
    private static final long TTL_MINUTES = 60;

    /** Maximum number of requests kept in memory. Oldest are evicted first when exceeded. */
    private static final int MAX_ENTRIES = 5000;

    private final Map<String, LeetCodeRecommendRequest> requestStore = new ConcurrentHashMap<>();
    private final Map<String, List<LeetCodeRecommendItem>> itemStore = new ConcurrentHashMap<>();

    public LeetCodeRecommendRequest createPendingRequest(Integer studentId, String scene, int limit) {
        String requestId = UUID.randomUUID().toString();
        LeetCodeRecommendRequest request = new LeetCodeRecommendRequest(requestId, studentId, scene, limit);
        request.setCreatedAt(LocalDateTime.now());
        request.setStatus(LeetCodeRecommendRequest.STATUS_PENDING);
        requestStore.put(requestId, request);
        return request;
    }

    public void completeRequest(String requestId, List<LeetCodeRecommendItem> items) {
        LeetCodeRecommendRequest request = requestStore.get(requestId);
        if (request == null) {
            return;
        }
        request.setStatus(LeetCodeRecommendRequest.STATUS_COMPLETED);
        request.setFinishedAt(LocalDateTime.now());
        itemStore.put(requestId, items == null ? Collections.emptyList() : new ArrayList<>(items));
    }

    public void failRequest(String requestId, String errorMessage) {
        LeetCodeRecommendRequest request = requestStore.get(requestId);
        if (request == null) {
            return;
        }
        request.setStatus(LeetCodeRecommendRequest.STATUS_FAILED);
        request.setErrorMessage(errorMessage);
        request.setFinishedAt(LocalDateTime.now());
        itemStore.put(requestId, Collections.emptyList());
    }

    public LeetCodeRecommendRequest getRequest(String requestId) {
        return requestStore.get(requestId);
    }

    public List<LeetCodeRecommendItem> getItems(String requestId) {
        return new ArrayList<>(itemStore.getOrDefault(requestId, Collections.emptyList()));
    }

    /**
     * Periodically evict expired entries to prevent unbounded memory growth.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void evictExpiredEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(TTL_MINUTES);
        int evicted = 0;

        Iterator<Map.Entry<String, LeetCodeRecommendRequest>> it = requestStore.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LeetCodeRecommendRequest> entry = it.next();
            LeetCodeRecommendRequest req = entry.getValue();
            if (req.getCreatedAt() != null && req.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                itemStore.remove(entry.getKey());
                evicted++;
            }
        }

        // Hard cap: if still over MAX_ENTRIES, evict oldest
        if (requestStore.size() > MAX_ENTRIES) {
            List<Map.Entry<String, LeetCodeRecommendRequest>> sorted = new ArrayList<>(requestStore.entrySet());
            sorted.sort((a, b) -> {
                LocalDateTime ta = a.getValue().getCreatedAt();
                LocalDateTime tb = b.getValue().getCreatedAt();
                if (ta == null && tb == null) return 0;
                if (ta == null) return -1;
                if (tb == null) return 1;
                return ta.compareTo(tb);
            });
            int toRemove = requestStore.size() - MAX_ENTRIES;
            for (int i = 0; i < toRemove && i < sorted.size(); i++) {
                String key = sorted.get(i).getKey();
                requestStore.remove(key);
                itemStore.remove(key);
                evicted++;
            }
        }

        if (evicted > 0) {
            log.info("Evicted {} expired recommendation request(s), remaining: {}", evicted, requestStore.size());
        }
    }
}
