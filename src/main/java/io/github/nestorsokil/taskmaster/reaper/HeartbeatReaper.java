package io.github.nestorsokil.taskmaster.reaper;

import io.github.nestorsokil.taskmaster.config.TaskmasterMetrics;
import io.github.nestorsokil.taskmaster.config.TaskmasterConfig;
import io.github.nestorsokil.taskmaster.repository.TaskRepository;
import io.github.nestorsokil.taskmaster.repository.WorkerRepository;
import io.github.nestorsokil.taskmaster.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.List;

/**
 * Periodically sweeps for silent workers and requeues their abandoned tasks.
 *
 * <p>Runs every {@code taskmaster.reaper.interval-ms} (default 15 s).
 * All three steps — mark stale, mark dead, requeue tasks — are wrapped in a single
 * transaction so the database never sees an inconsistent intermediate state
 * (e.g. a worker marked DEAD whose tasks are still RUNNING).
 *
 * <p>Emits a {@code tasks.requeued} Micrometer counter tagged with
 * {@code reason=worker_dead} for each task returned to the PENDING queue.
 */
@Slf4j
@RequiredArgsConstructor
public class HeartbeatReaper {

    private final DSLContext dsl;
    private final WorkerRepository workerRepository;
    private final TaskRepository taskRepository;
    private final TaskmasterConfig config;
    private final TaskmasterMetrics metrics;
    private final WebhookService webhookService;

    public void reap() {
        long staleSeconds = config.heartbeat().staleThresholdSeconds();
        long deadSeconds  = config.heartbeat().deadThresholdSeconds();

        // All three steps are atomic: a worker must not appear DEAD without its tasks requeued
        dsl.transaction(cfg -> {
            var tx = DSL.using(cfg);

            // Step 1: ACTIVE → STALE
            int staled = workerRepository.withDsl(tx).markStale(staleSeconds);
            if (staled > 0) {
                log.warn("Marked {} worker(s) STALE", staled);
            }

            // Step 2: ACTIVE/STALE → DEAD; get their IDs to requeue tasks
            List<String> deadWorkerIds = workerRepository.withDsl(tx).markDeadAndReturnIds(deadSeconds);
            if (deadWorkerIds.isEmpty()) {
                return;
            }
            log.warn("Marked {} worker(s) DEAD: {}", deadWorkerIds.size(), deadWorkerIds);
            metrics.workersDied(deadWorkerIds.size());

            // Step 3: requeue RUNNING tasks owned by dead workers (or dead-letter if exhausted)
            var affected = taskRepository.withDsl(tx).requeueOrMarkDeadFromDeadWorkers(deadWorkerIds);

            int requeueCount = 0;

            for (var task : affected) {
                if ("PENDING".equals(task.status())) {
                    requeueCount++;
                    continue;
                }
                metrics.taskDeadLettered(task.queueName(), "worker_dead");
                webhookService.deliverIfConfigured(task);
            }

            if (requeueCount > 0) {
                metrics.tasksRequeued("worker_dead", requeueCount);
                log.info("Requeued {} task(s) from dead workers", requeueCount);
            }
        });
    }
}
