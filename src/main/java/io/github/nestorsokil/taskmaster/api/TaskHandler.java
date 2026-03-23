package io.github.nestorsokil.taskmaster.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nestorsokil.taskmaster.api.dto.*;
import io.github.nestorsokil.taskmaster.domain.Tags;
import io.github.nestorsokil.taskmaster.service.ClaimService;
import io.github.nestorsokil.taskmaster.service.ObservabilityService;
import io.github.nestorsokil.taskmaster.service.TaskService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.UUID;

@RequiredArgsConstructor
public class TaskHandler {

    private final TaskService taskService;
    private final ClaimService claimService;
    private final ObservabilityService observabilityService;
    private final ObjectMapper objectMapper;

    public void submit(Context ctx) {
        var req = ctx.bodyAsClass(SubmitTaskRequest.class);
        if (req.queueName() == null || req.queueName().isBlank()) throw new BadRequestResponse("queueName is required");
        if (req.payload() == null) throw new BadRequestResponse("payload is required");
        if (req.maxAttempts() < 1) throw new BadRequestResponse("maxAttempts must be at least 1");
        var task = taskService.submit(
                req.queueName(),
                toJson(req.payload()),
                req.priority(),
                req.maxAttempts(),
                req.deadline(),
                new Tags(req.tags()),
                req.callbackUrl()
        );
        ctx.status(202).json(new SubmitTaskResponse(task.id(), task.status()));
    }

    public void get(Context ctx) {
        ctx.json(TaskResponse.from(taskService.getTask(UUID.fromString(ctx.pathParam("taskId")))));
    }

    public void list(Context ctx) {
        String queue  = ctx.queryParam("queue");
        String status = ctx.queryParam("status");
        int limit     = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        ctx.json(observabilityService.getTasks(queue, status, limit).stream().map(TaskResponse::from).toList());
    }

    public void claim(Context ctx) {
        var req = ctx.bodyAsClass(ClaimTasksRequest.class);
        if (req.workerId() == null || req.workerId().isBlank()) throw new BadRequestResponse("workerId is required");
        if (req.queueName() == null || req.queueName().isBlank()) throw new BadRequestResponse("queueName is required");
        if (req.maxTasks() < 1 || req.maxTasks() > 100) throw new BadRequestResponse("maxTasks must be between 1 and 100");

        var claimed = claimService.claim(req.workerId(), req.queueName(), req.maxTasks());
        var tasks = new ArrayList<ClaimTasksResponse.ClaimedTask>();
        for (var task : claimed) {
            tasks.add(new ClaimTasksResponse.ClaimedTask(task.id(), fromJson(task.payload()), task.attempts()));
        }
        ctx.json(new ClaimTasksResponse(tasks));
    }

    public void complete(Context ctx) {
        var req = ctx.bodyAsClass(CompleteTaskRequest.class);
        if (req.workerId() == null || req.workerId().isBlank()) throw new BadRequestResponse("workerId is required");
        taskService.complete(UUID.fromString(ctx.pathParam("taskId")), req.workerId(), req.result());
        ctx.status(204);
    }

    public void fail(Context ctx) {
        var req = ctx.bodyAsClass(FailTaskRequest.class);
        if (req.workerId() == null || req.workerId().isBlank()) throw new BadRequestResponse("workerId is required");
        if (req.error() == null || req.error().isBlank()) throw new BadRequestResponse("error is required");
        taskService.fail(UUID.fromString(ctx.pathParam("taskId")), req.workerId(), req.error());
        ctx.status(204);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new BadRequestResponse("Invalid payload JSON");
        }
    }

    private JsonNode fromJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse stored payload", e);
        }
    }
}
