package com.opsautoagent.domain.codeops.agent.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Priority-ordered incident queue with file-based persistence.
 *
 * Ordering:
 *   1. Severity (CRITICAL > HIGH > WARNING > LOW)
 *   2. Recency (newer first for same severity)
 *
 * Persistence: data/incident-queue/queue.json survives restarts.
 */
@Slf4j
@Service
public class IncidentPriorityQueue {

    private static final Path QUEUE_FILE = Path.of("data/incident-queue/queue.json");

    private final PriorityBlockingQueue<QueuedIncident> queue = new PriorityBlockingQueue<>(
            100, Comparator
                    .comparingInt(QueuedIncident::severityScore).reversed()
                    .thenComparingLong(QueuedIncident::getEnqueuedAt));

    private int totalEnqueued;
    private int totalDispatched;

    /**
     * Add an aggregated incident to the queue.
     */
    public QueuedIncident enqueue(IncidentDedupService.AggregatedIncident incident) {
        QueuedIncident qi = new QueuedIncident(
                incident.getGroupKey(), incident.getService(), incident.getAlertName(),
                incident.getHighestSeverity(), incident.getAlertCount(),
                incident.getLatestSummary(), incident.getAffectedEndpoints(),
                Instant.now().toEpochMilli());
        queue.offer(qi);
        totalEnqueued++;
        persist();
        log.info("Queued incident [{}]: service={}, severity={}, alerts={}, queueSize={}",
                qi.groupKey, qi.service, qi.severity, qi.alertCount, queue.size());
        return qi;
    }

    /**
     * Dequeue the highest-priority incident.
     */
    public QueuedIncident dequeue() {
        QueuedIncident qi = queue.poll();
        if (qi != null) {
            qi.status = "DISPATCHED";
            totalDispatched++;
            persist();
        }
        return qi;
    }

    /**
     * Peek top N without removing.
     */
    public List<QueuedIncident> peekTopN(int n) {
        List<QueuedIncident> all = new ArrayList<>(queue);
        all.sort(Comparator
                .comparingInt(QueuedIncident::severityScore).reversed()
                .thenComparingLong(QueuedIncident::getEnqueuedAt));
        return all.subList(0, Math.min(n, all.size()));
    }

    public int size() { return queue.size(); }
    public int getTotalEnqueued() { return totalEnqueued; }
    public int getTotalDispatched() { return totalDispatched; }

    public List<Map<String, Object>> listQueued(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return peekTopN(safeLimit).stream().map(QueuedIncident::toMap).toList();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("queueSize", queue.size());
        stats.put("totalEnqueued", totalEnqueued);
        stats.put("totalDispatched", totalDispatched);
        stats.put("top3", peekTopN(3).stream().map(QueuedIncident::toSummary).toList());
        return stats;
    }

    /**
     * Persist queue state to file.
     */
    private void persist() {
        try {
            Files.createDirectories(QUEUE_FILE.getParent());
            List<Map<String, Object>> items = new ArrayList<>();
            for (QueuedIncident qi : queue) items.add(qi.toMap());
            Map<String, Object> state = Map.of(
                    "totalEnqueued", totalEnqueued,
                    "totalDispatched", totalDispatched,
                    "queue", items);
            Files.writeString(QUEUE_FILE,
                    com.alibaba.fastjson.JSON.toJSONString(state),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    public static class QueuedIncident {
        private final String groupKey;
        private final String service;
        private final String alertName;
        private final String severity;
        private final int alertCount;
        private final String summary;
        private final List<String> endpoints;
        private final long enqueuedAt;
        private String status = "QUEUED";

        public QueuedIncident(String groupKey, String service, String alertName,
                               String severity, int alertCount, String summary,
                               List<String> endpoints, long enqueuedAt) {
            this.groupKey = groupKey; this.service = service; this.alertName = alertName;
            this.severity = severity; this.alertCount = alertCount;
            this.summary = summary; this.endpoints = endpoints; this.enqueuedAt = enqueuedAt;
        }

        int severityScore() { return IncidentDedupService.AggregatedIncident.severityScore(severity); }
        public String getGroupKey() { return groupKey; }
        public String getService() { return service; }
        public String getAlertName() { return alertName; }
        public String getSeverity() { return severity; }
        public int getAlertCount() { return alertCount; }
        public String getSummary() { return summary; }
        public List<String> getEndpoints() { return endpoints; }
        public long getEnqueuedAt() { return enqueuedAt; }
        public String getStatus() { return status; }

        public Map<String, Object> toSummary() {
            return Map.of("groupKey", groupKey, "service", service, "severity", severity,
                    "alertCount", alertCount, "status", status);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupKey", groupKey);
            m.put("service", service);
            m.put("severity", severity);
            m.put("alertCount", alertCount);
            m.put("status", status);
            m.put("enqueuedAt", enqueuedAt);
            return m;
        }
    }
}
