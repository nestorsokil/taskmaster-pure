package io.github.nestorsokil.taskmaster.domain;

import java.time.Instant;

public record Worker(

        String id,
        String queueName,
        int maxConcurrency,
        Instant registeredAt,
        Instant lastHeartbeat,
        Tags tags,
        String status

) {
    public Worker {
        if (tags == null) tags = Tags.EMPTY;
    }
}
