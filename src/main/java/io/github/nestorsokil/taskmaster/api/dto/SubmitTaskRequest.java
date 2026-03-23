package io.github.nestorsokil.taskmaster.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record SubmitTaskRequest(
        String queueName,
        JsonNode payload,
        int priority,
        Integer maxAttempts,
        Instant deadline,
        List<String> tags,
        String callbackUrl
) {
    public SubmitTaskRequest {
        if (maxAttempts == null) maxAttempts = 3;
        if (tags == null) tags = List.of();
    }
}
