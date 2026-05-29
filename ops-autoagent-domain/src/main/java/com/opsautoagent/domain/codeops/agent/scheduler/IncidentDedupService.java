package com.opsautoagent.domain.codeops.agent.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alert deduplication and aggregation for incident storms.
 *
 * Strategy:
 *   - Same fingerprint → dedup (only notify once within dedup window)
 *   - Same service + same severity → aggregate into one incident
 *   - Multiple services affected → group by service, create one incident per service
 *
 * During alert storms (1000+ alerts), this collapses them into a handful of distinct incidents.
 */
@Slf4j
@Service
public class IncidentDedupService {

    private final ConcurrentHashMap<String, Long> seenFingerprints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AggregatedIncident> activeIncidents = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Process an incoming alert. Returns null if it's a duplicate within the dedup window.
     */
    public AggregatedIncident ingest(String fingerprint, String alertName, String service,
                                      String severity, String summary, String endpoint) {
        // Dedup by fingerprint within window
        long now = Instant.now().toEpochMilli();
        Long lastSeen = seenFingerprints.get(fingerprint);
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
            return null; // duplicate, skip
        }
        seenFingerprints.put(fingerprint, now);

        // Aggregate by service group
        String groupKey = service + "|" + alertName;
        AggregatedIncident incident = activeIncidents.computeIfAbsent(groupKey,
                k -> new AggregatedIncident(groupKey, service, alertName));
        incident.addAlert(severity, summary, endpoint, now);
        incident.touch();

        return incident;
    }

    /**
     * Get all currently active aggregated incidents, ordered by severity then recency.
     */
    public List<AggregatedIncident> getActiveIncidents() {
        List<AggregatedIncident> list = new ArrayList<>(activeIncidents.values());
        list.sort(Comparator
                .comparingInt((AggregatedIncident a) -> a.severityScore()).reversed()
                .thenComparingLong(a -> a.getLastUpdate()));
        return list;
    }

    /**
     * Mark an incident as dispatched (removes from active set).
     */
    public void markDispatched(String groupKey) {
        activeIncidents.remove(groupKey);
    }

    /**
     * Clean up expired entries from the fingerprint cache.
     */
    public void cleanup() {
        long cutoff = Instant.now().toEpochMilli() - DEDUP_WINDOW_MS * 2;
        seenFingerprints.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public static class AggregatedIncident {
        private final String groupKey;
        private final String service;
        private final String alertName;
        private final List<String> affectedEndpoints = new ArrayList<>();
        private String highestSeverity = "LOW";
        private int alertCount;
        private String latestSummary;
        private long firstSeen;
        private long lastUpdate;

        public AggregatedIncident(String groupKey, String service, String alertName) {
            this.groupKey = groupKey;
            this.service = service;
            this.alertName = alertName;
            this.firstSeen = Instant.now().toEpochMilli();
        }

        void addAlert(String severity, String summary, String endpoint, long now) {
            alertCount++;
            if (severityScore(severity) > severityScore(highestSeverity)) {
                highestSeverity = severity;
            }
            if (endpoint != null && !affectedEndpoints.contains(endpoint)) {
                affectedEndpoints.add(endpoint);
            }
            latestSummary = summary;
            lastUpdate = now;
        }

        void touch() { lastUpdate = Instant.now().toEpochMilli(); }

        int severityScore() { return severityScore(highestSeverity); }

        static int severityScore(String s) {
            return switch (s.toUpperCase()) {
                case "CRITICAL" -> 100;
                case "HIGH" -> 70;
                case "WARNING", "MEDIUM" -> 40;
                default -> 10;
            };
        }

        public String getGroupKey() { return groupKey; }
        public String getService() { return service; }
        public String getAlertName() { return alertName; }
        public String getHighestSeverity() { return highestSeverity; }
        public int getAlertCount() { return alertCount; }
        public String getLatestSummary() { return latestSummary; }
        public long getLastUpdate() { return lastUpdate; }
        public long getFirstSeen() { return firstSeen; }
        public List<String> getAffectedEndpoints() { return affectedEndpoints; }
    }
}
