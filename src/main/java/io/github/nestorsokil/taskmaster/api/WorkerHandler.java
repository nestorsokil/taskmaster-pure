package io.github.nestorsokil.taskmaster.api;

import io.github.nestorsokil.taskmaster.api.dto.RegisterWorkerRequest;
import io.github.nestorsokil.taskmaster.api.dto.WorkerResponse;
import io.github.nestorsokil.taskmaster.config.TaskmasterMetrics;
import io.github.nestorsokil.taskmaster.domain.Tags;
import io.github.nestorsokil.taskmaster.repository.WorkerRepository;
import io.github.nestorsokil.taskmaster.service.ObservabilityService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WorkerHandler {

    private final WorkerRepository workerRepository;
    private final ObservabilityService observabilityService;
    private final TaskmasterMetrics metrics;

    public void register(Context ctx) {
        var req = ctx.bodyAsClass(RegisterWorkerRequest.class);
        if (req.workerId() == null || req.workerId().isBlank()) throw new BadRequestResponse("workerId is required");
        if (req.queueName() == null || req.queueName().isBlank()) throw new BadRequestResponse("queueName is required");
        workerRepository.upsert(req.workerId(), req.queueName(), req.maxConcurrency(), new Tags(req.tags()));
        metrics.workerRegistered(req.queueName());
    }

    public void heartbeat(Context ctx) {
        String workerId = ctx.pathParam("workerId");
        if (workerRepository.updateHeartbeat(workerId) == 0) {
            throw new NotFoundResponse("Worker not registered: " + workerId);
        }
        ctx.status(204);
    }

    public void list(Context ctx) {
        ctx.json(observabilityService.getWorkers().stream().map(WorkerResponse::from).toList());
    }
}
