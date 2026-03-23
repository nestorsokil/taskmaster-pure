package io.github.nestorsokil.taskmaster.api.dto;

public record FailTaskRequest(String workerId, String error) {}
