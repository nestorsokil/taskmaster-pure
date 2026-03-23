package io.github.nestorsokil.taskmaster.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record Task(

        UUID id,
        String queueName,
        String payload,
        int priority,
        String status,
        String workerId,
        int attempts,
        int maxAttempts,
        Instant createdAt,
        Instant claimedAt,
        Instant finishedAt,
        Instant nextAttemptAt,
        String result,
        String lastError,
        Instant deadline,
        Tags tags,
        String callbackUrl

) {
    public Task {
        if (tags == null) tags = Tags.EMPTY;
    }
}
