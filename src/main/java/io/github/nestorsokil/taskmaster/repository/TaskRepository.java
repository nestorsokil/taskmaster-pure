package io.github.nestorsokil.taskmaster.repository;

import io.github.nestorsokil.taskmaster.domain.Tags;
import io.github.nestorsokil.taskmaster.domain.Task;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.postgresql.util.PGobject;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.list;
import static org.jooq.impl.DSL.val;

@RequiredArgsConstructor
public class TaskRepository {

    private final DSLContext dsl;

    public TaskRepository withDsl(DSLContext ctx) {
        return new TaskRepository(ctx);
    }

    public Task save(Task task) {
        return dsl.fetch("""
                INSERT INTO tasks (queue_name, payload, priority, status, max_attempts, deadline, tags, callback_url)
                VALUES (?, ?::jsonb, ?, ?, ?, ?, ?::text[], ?)
                RETURNING *
                """,
                task.queueName(),
                task.payload(),
                task.priority(),
                task.status(),
                task.maxAttempts(),
                toOffsetDateTime(task.deadline()),
                toArrayLiteral(task.tags()),
                task.callbackUrl()
        ).map(this::toTask).getFirst();
    }

    public Optional<Task> findById(UUID id) {
        return dsl.fetch("SELECT * FROM tasks WHERE id = ?", id)
                .map(this::toTask)
                .stream()
                .findFirst();
    }

    public List<Task> claimTasks(String workerId, String queueName, int maxTasks) {
        return dsl.fetch("""
                UPDATE tasks
                   SET status     = 'RUNNING',
                       worker_id  = ?,
                       claimed_at = now(),
                       attempts   = attempts + 1
                 WHERE id IN (
                       SELECT id FROM tasks
                        WHERE queue_name = ?
                          AND status     = 'PENDING'
                          AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                          AND tags <@ (SELECT tags FROM workers WHERE id = ?)
                        ORDER BY priority DESC, created_at ASC
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                 )
                RETURNING *
                """, workerId, queueName, workerId, maxTasks)
                .map(this::toTask);
    }

    public int completeTask(UUID taskId, String workerId, String result) {
        return dsl.execute("""
                UPDATE tasks
                   SET status      = 'DONE',
                       result      = ?,
                       finished_at = now(),
                       worker_id   = NULL
                 WHERE id        = ?
                   AND worker_id = ?
                   AND status    = 'RUNNING'
                """, result, taskId, workerId);
    }

    public List<Task> failTask(UUID taskId, String workerId, String error, double baseDelay) {
        return dsl.fetch("""
                UPDATE tasks
                   SET status          = CASE
                                           WHEN attempts >= max_attempts THEN 'DEAD'
                                           ELSE 'PENDING'
                                         END,
                       last_error      = ?,
                       worker_id       = NULL,
                       finished_at     = now(),
                       next_attempt_at = CASE
                                           WHEN attempts >= max_attempts THEN NULL
                                           ELSE now() + least(
                                                    make_interval(secs => power(2, attempts) * ?),
                                                    interval '5 minutes'
                                                )
                                         END
                 WHERE id        = ?
                   AND worker_id = ?
                   AND status    = 'RUNNING'
                RETURNING *
                """, error, baseDelay, taskId, workerId)
                .map(this::toTask);
    }

    public List<Task> requeueOrMarkDeadFromDeadWorkers(List<String> workerIds) {
        var params = list(workerIds.stream().map(id -> val(id)).collect(Collectors.toList()));
        return dsl.fetch("""
                UPDATE tasks
                   SET status     = CASE
                                      WHEN attempts >= max_attempts THEN 'DEAD'
                                      ELSE 'PENDING'
                                    END,
                       worker_id  = NULL,
                       claimed_at = NULL
                 WHERE status    = 'RUNNING'
                   AND worker_id IN ({0})
                RETURNING *
                """, params)
                .map(this::toTask);
    }

    public List<Task> findFiltered(String queueName, String status, int limit) {
        return dsl.fetch("""
                SELECT * FROM tasks
                 WHERE (CAST(? AS TEXT) IS NULL OR queue_name = ?)
                   AND (CAST(? AS TEXT) IS NULL OR status     = ?)
                 ORDER BY created_at DESC
                 LIMIT ?
                """, queueName, queueName, status, status, limit)
                .map(this::toTask);
    }

    public List<Task> deadlineExpired() {
        return dsl.fetch("""
                UPDATE tasks
                   SET status      = 'DEAD',
                       finished_at = now()
                 WHERE status   = 'PENDING'
                   AND deadline IS NOT NULL
                   AND deadline  < now()
                RETURNING *
                """)
                .map(this::toTask);
    }

    public int deleteExpiredTerminalTasks(long ttlSeconds, int batchSize) {
        return dsl.execute("""
                DELETE FROM tasks
                 WHERE id IN (
                       SELECT id FROM tasks
                        WHERE status IN ('DONE', 'DEAD')
                          AND finished_at < now() - CAST(? || ' seconds' AS interval)
                        LIMIT ?
                 )
                """, ttlSeconds, batchSize);
    }

    public List<QueueStats> getQueueStats() {
        return dsl.fetch("""
                SELECT queue_name,
                       COUNT(*) FILTER (WHERE status = 'PENDING')                            AS pending,
                       COUNT(*) FILTER (WHERE status = 'RUNNING')                            AS running,
                       COUNT(*) FILTER (WHERE status = 'PENDING' AND last_error IS NOT NULL) AS failed,
                       COUNT(*) FILTER (WHERE status = 'DEAD')                               AS dead
                  FROM tasks
                 GROUP BY queue_name
                """)
                .map(r -> new QueueStats(
                        r.get("queue_name", String.class),
                        r.get("pending", Long.class),
                        r.get("running", Long.class),
                        r.get("failed", Long.class),
                        r.get("dead", Long.class)
                ));
    }

    public record QueueStats(String queue_name, long pending, long running, long failed, long dead) {}

    // --- mapping ---

    private Task toTask(Record r) {
        return new Task(
                r.get("id", UUID.class),
                r.get("queue_name", String.class),
                toJsonString(r.get("payload")),
                r.get("priority", int.class),
                r.get("status", String.class),
                r.get("worker_id", String.class),
                r.get("attempts", int.class),
                r.get("max_attempts", int.class),
                toInstant(r.get("created_at")),
                toInstant(r.get("claimed_at")),
                toInstant(r.get("finished_at")),
                toInstant(r.get("next_attempt_at")),
                r.get("result", String.class),
                r.get("last_error", String.class),
                toInstant(r.get("deadline")),
                toTags(r.get("tags")),
                r.get("callback_url", String.class)
        );
    }

    private static String toJsonString(Object val) {
        return switch (val) {
            case null -> null;
            case PGobject pg -> pg.getValue();
            case String s -> s;
            default -> val.toString();
        };
    }

    static Instant toInstant(Object val) {
        return switch (val) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime odt -> odt.toInstant();
            case Timestamp ts -> ts.toInstant();
            default -> throw new IllegalArgumentException("Cannot convert to Instant: " + val.getClass());
        };
    }

    static Tags toTags(Object val) {
        return switch (val) {
            case null -> Tags.EMPTY;
            case String[] arr -> new Tags(List.of(arr));
            case Array sqlArr -> {
                try {
                    String[] arr = (String[]) sqlArr.getArray();
                    yield new Tags(arr == null ? List.of() : List.of(arr));
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to read tags array", e);
                }
            }
            default -> Tags.EMPTY;
        };
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static String toArrayLiteral(Tags tags) {
        if (tags == null || tags.values().isEmpty()) return "{}";
        return "{" + tags.values().stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "}";
    }
}
