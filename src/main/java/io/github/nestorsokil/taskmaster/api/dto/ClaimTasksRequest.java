package io.github.nestorsokil.taskmaster.api.dto;

public record ClaimTasksRequest(String workerId, String queueName, int maxTasks) {}
