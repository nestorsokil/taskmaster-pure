package io.github.nestorsokil.taskmaster.repository;

import io.github.nestorsokil.taskmaster.domain.Tags;
import io.github.nestorsokil.taskmaster.domain.Worker;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;

@RequiredArgsConstructor
public class WorkerRepository {

    private final DSLContext dsl;

    public WorkerRepository withDsl(DSLContext ctx) {
        return new WorkerRepository(ctx);
    }

    public void upsert(String id, String queueName, int maxConcurrency, Tags tags) {
        dsl.execute("""
                INSERT INTO workers (id, queue_name, max_concurrency, tags, registered_at, last_heartbeat, status)
                VALUES (?, ?, ?, ?::text[], now(), now(), 'ACTIVE')
                ON CONFLICT (id) DO UPDATE
                   SET queue_name      = EXCLUDED.queue_name,
                       max_concurrency = EXCLUDED.max_concurrency,
                       tags            = EXCLUDED.tags,
                       last_heartbeat  = now(),
                       status          = 'ACTIVE'
                """, id, queueName, maxConcurrency, TaskRepository.toArrayLiteral(tags));
    }

    public void ensureExists(String id, String queueName, int maxConcurrency) {
        dsl.execute("""
                INSERT INTO workers (id, queue_name, max_concurrency, tags, registered_at, last_heartbeat, status)
                VALUES (?, ?, ?, ARRAY[]::TEXT[], now(), now(), 'ACTIVE')
                ON CONFLICT (id) DO UPDATE
                   SET last_heartbeat = now(),
                       status         = 'ACTIVE'
                """, id, queueName, maxConcurrency);
    }

    public int updateHeartbeat(String workerId) {
        return dsl.execute("UPDATE workers SET last_heartbeat = now() WHERE id = ?", workerId);
    }

    public List<Worker> findActive() {
        return dsl.fetch("SELECT * FROM workers WHERE status = 'ACTIVE'").map(this::toWorker);
    }

    public List<Worker> findAll() {
        return dsl.fetch("SELECT * FROM workers").map(this::toWorker);
    }

    public int deleteExpiredDeadWorkers(long ttlSeconds, int batchSize) {
        return dsl.execute("""
                DELETE FROM workers
                 WHERE id IN (
                       SELECT w.id FROM workers w
                        WHERE w.status = 'DEAD'
                          AND w.last_heartbeat < now() - CAST(? || ' seconds' AS interval)
                        LIMIT ?
                 )
                """, ttlSeconds, batchSize);
    }

    public int markStale(long thresholdSeconds) {
        return dsl.execute("""
                UPDATE workers
                   SET status = 'STALE'
                 WHERE status = 'ACTIVE'
                   AND last_heartbeat < now() - make_interval(secs => ?)
                """, thresholdSeconds);
    }

    public List<String> markDeadAndReturnIds(long thresholdSeconds) {
        return dsl.fetch("""
                UPDATE workers
                   SET status = 'DEAD'
                 WHERE status IN ('ACTIVE', 'STALE')
                   AND last_heartbeat < now() - make_interval(secs => ?)
                RETURNING id
                """, thresholdSeconds)
                .getValues("id", String.class);
    }

    // --- mapping ---

    private Worker toWorker(Record r) {
        return new Worker(
                r.get("id", String.class),
                r.get("queue_name", String.class),
                r.get("max_concurrency", int.class),
                TaskRepository.toInstant(r.get("registered_at")),
                TaskRepository.toInstant(r.get("last_heartbeat")),
                TaskRepository.toTags(r.get("tags")),
                r.get("status", String.class)
        );
    }
}
