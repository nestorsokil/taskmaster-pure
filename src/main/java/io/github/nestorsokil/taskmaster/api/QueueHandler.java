package io.github.nestorsokil.taskmaster.api;

import io.github.nestorsokil.taskmaster.api.dto.QueueSummaryResponse;
import io.github.nestorsokil.taskmaster.service.ObservabilityService;
import io.javalin.http.Context;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class QueueHandler {

    private final ObservabilityService observabilityService;

    public void list(Context ctx) {
        ctx.json(observabilityService.getQueueSummaries().stream().map(QueueSummaryResponse::from).toList());
    }
}
