package io.github.nestorsokil.taskmaster.api.dto;

import java.util.List;

public record RegisterWorkerRequest(String workerId, String queueName, Integer maxConcurrency, List<String> tags) {
    public RegisterWorkerRequest {
        if (maxConcurrency == null) maxConcurrency = 4;
        if (tags == null) tags = List.of();
    }
}
