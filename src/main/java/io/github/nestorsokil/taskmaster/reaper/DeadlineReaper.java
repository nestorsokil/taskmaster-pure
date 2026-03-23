package io.github.nestorsokil.taskmaster.reaper;

import io.github.nestorsokil.taskmaster.config.TaskmasterMetrics;
import io.github.nestorsokil.taskmaster.repository.TaskRepository;
import io.github.nestorsokil.taskmaster.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically dead-letters tasks whose submission deadline has passed.
 *
 * <p>Runs every 30 seconds. A task is dead-lettered when its {@code deadline}
 * column is set and the deadline has passed while the task is still PENDING
 * (i.e. it was never claimed in time).
 */
@Slf4j
@RequiredArgsConstructor
public class DeadlineReaper {

    private final TaskRepository taskRepository;
    private final TaskmasterMetrics metrics;
    private final WebhookService webhookService;

    public void reap() {
        var deadLettered = taskRepository.deadlineExpired();
        if (!deadLettered.isEmpty()) {
            deadLettered.forEach(task -> metrics.taskDeadLettered(task.queueName(), "deadline"));
            log.warn("Dead-lettered {} task(s) past their deadline", deadLettered.size());
            deadLettered.forEach(webhookService::deliverIfConfigured);
        }
    }
}
