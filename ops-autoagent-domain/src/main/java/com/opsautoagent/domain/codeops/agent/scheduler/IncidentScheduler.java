package com.opsautoagent.domain.codeops.agent.scheduler;

import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.service.EngineeringTaskAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central incident scheduler — aggregates alerts, prioritizes incidents,
 * enforces concurrency limits, and dispatches repair tasks.
 *
 * During alert storms:
 *   1000 alerts → dedup → 12 aggregated incidents → queue by priority
 *   → dispatch top-3 (global concurrency limit) → per-service max 1
 *   → remaining 9 wait in queue → auto-expire after timeout
 *
 * Interview-ready summary:
 *   "I didn't just handle one case. I built a queue scheduler that handles
 *    alert storms — 1000 alerts collapse into 12 distinct incidents,
 *    only the top-3 run simultaneously, per-service cap prevents thundering herd."
 */
@Slf4j
@Service
public class IncidentScheduler {

    @Resource
    private IncidentDedupService dedupService;

    @Resource
    private IncidentPriorityQueue priorityQueue;

    @Resource
    private EngineeringTaskAgentService engineeringTaskAgentService;

    @Value("${codeops.scheduler.max-concurrent:3}")
    private int maxConcurrent;

    @Value("${codeops.scheduler.max-per-service:1}")
    private int maxPerService;

    @Value("${codeops.scheduler.dispatch-delay-ms:5000}")
    private long dispatchDelayMs;

    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger();
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "incident-scheduler-" + count.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final ExecutorService taskExecutor = Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger();
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "incident-task-" + count.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final Semaphore globalSemaphore = new Semaphore(3);
    private final ConcurrentHashMap<String, Semaphore> perServiceSemaphores = new ConcurrentHashMap<>();

    private volatile boolean running;

    @PostConstruct
    public void init() {
        start();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    /**
     * Ingest an alert into the scheduling pipeline.
     * Returns null if deduplicated, or the queued incident if accepted.
     */
    public IncidentPriorityQueue.QueuedIncident ingest(
            String fingerprint, String alertName, String service,
            String severity, String summary, String endpoint) {

        IncidentDedupService.AggregatedIncident aggregated = dedupService.ingest(
                fingerprint, alertName, service, severity, summary, endpoint);

        if (aggregated == null) {
            log.debug("Alert deduped: fingerprint={}, service={}, severity={}",
                    fingerprint, service, severity);
            return null;
        }

        // Only enqueue once per aggregated incident (when alertCount == 1)
        if (aggregated.getAlertCount() == 1) {
            return priorityQueue.enqueue(aggregated);
        }

        return null;
    }

    /**
     * Start the background dispatch loop.
     */
    public void start() {
        if (running) return;
        running = true;
        dispatchExecutor.submit(this::dispatchLoop);
        log.info("IncidentScheduler started. maxConcurrent={}, maxPerService={}", maxConcurrent, maxPerService);
    }

    /**
     * Main dispatch loop — polls queue, acquires semaphores, dispatches tasks.
     */
    private void dispatchLoop() {
        while (running) {
            try {
                List<IncidentPriorityQueue.QueuedIncident> candidates = priorityQueue.peekTopN(maxConcurrent * 2);
                if (candidates.isEmpty()) {
                    Thread.sleep(dispatchDelayMs);
                    continue;
                }

                int dispatched = 0;
                for (IncidentPriorityQueue.QueuedIncident qi : candidates) {
                    if (dispatched >= maxConcurrent) break;

                    // Check global concurrency
                    if (!globalSemaphore.tryAcquire()) continue;

                    // Check per-service concurrency
                    Semaphore serviceSem = perServiceSemaphores.computeIfAbsent(
                            qi.getService(), k -> new Semaphore(maxPerService));
                    if (!serviceSem.tryAcquire()) {
                        globalSemaphore.release();
                        continue;
                    }

                    // Dequeue and dispatch
                    IncidentPriorityQueue.QueuedIncident dequeued = priorityQueue.dequeue();
                    if (dequeued == null) {
                        globalSemaphore.release();
                        serviceSem.release();
                        continue;
                    }

                    dispatchRepair(dequeued);
                    dispatched++;
                }

                Thread.sleep(dispatchDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Scheduler dispatch error: {}", e.getMessage());
            }
        }
    }

    /**
     * Dispatch a queued incident to the CodeOps repair pipeline.
     */
    private void dispatchRepair(IncidentPriorityQueue.QueuedIncident qi) {
        log.info("DISPATCHING: service={}, severity={}, alerts={}, queueRemaining={}",
                qi.getService(), qi.getSeverity(), qi.getAlertCount(), priorityQueue.size());

        // Run the task asynchronously — the dispatch loop must not block
        taskExecutor.submit(() -> {
            try {
                EngineeringTaskEntity task = EngineeringTaskEntity.builder()
                        .taskType("INCIDENT_TO_FIX")
                        .goal(qi.getService() + " " + qi.getAlertName() + " severity=" + qi.getSeverity()
                                + ". Aggregated from " + qi.getAlertCount() + " alerts. " + qi.getSummary())
                        .repository("samples/order-service")
                        .focusAreas(List.of("incident", "code_location", "bug_fix", "test_verification", "release_risk"))
                        .context(Map.of(
                                "serviceName", qi.getService(),
                                "severity", qi.getSeverity(),
                                "alertCount", qi.getAlertCount(),
                                "scheduledBy", "IncidentScheduler"
                        ))
                        .maxRounds(8)
                        .maxToolCalls(50)
                        .build();
                engineeringTaskAgentService.submit(task);
                dedupService.markDispatched(qi.getGroupKey());
                log.info("Task completed: service={}, severity={}", qi.getService(), qi.getSeverity());
            } catch (Exception e) {
                log.error("Task execution failed for {}: {}", qi.getGroupKey(), e.getMessage());
            } finally {
                onTaskComplete(qi.getService());
            }
        });
    }

    /**
     * Release concurrency permits when a task completes.
     */
    public void onTaskComplete(String service) {
        Semaphore serviceSem = perServiceSemaphores.get(service);
        if (serviceSem != null) serviceSem.release();
        globalSemaphore.release();
    }

    public void stop() {
        running = false;
        dispatchExecutor.shutdown();
        taskExecutor.shutdown();
        try { dispatchExecutor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        try { taskExecutor.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running);
        status.put("maxConcurrent", maxConcurrent);
        status.put("maxPerService", maxPerService);
        status.put("activeIncidents", dedupService.getActiveIncidents().size());
        status.put("queueStats", priorityQueue.getStats());
        status.put("availableSlots", globalSemaphore.availablePermits());
        return status;
    }
}
