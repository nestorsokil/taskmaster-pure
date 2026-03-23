package io.github.nestorsokil.taskmaster.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ApiServer {

    private ApiServer() {}

    public static Javalin create(
            ObjectMapper objectMapper,
            PrometheusMeterRegistry registry,
            TaskHandler taskHandler,
            WorkerHandler workerHandler,
            QueueHandler queueHandler) {

        var app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;
            cfg.jsonMapper(new JavalinJackson(objectMapper, true));
        });

        app.before(ctx -> {
            var id = Optional.ofNullable(ctx.header("X-Correlation-Id")).orElse(UUID.randomUUID().toString());
            ctx.header("X-Correlation-Id", id);
        });

        app.post("/tasks/v1", taskHandler::submit);
        app.get("/tasks/v1", taskHandler::list);
        app.get("/tasks/v1/{taskId}", taskHandler::get);
        app.post("/tasks/v1/claim", taskHandler::claim);
        app.post("/tasks/v1/{taskId}/complete", taskHandler::complete);
        app.post("/tasks/v1/{taskId}/fail", taskHandler::fail);

        app.post("/workers/v1/register", workerHandler::register);
        app.post("/workers/v1/{workerId}/heartbeat", workerHandler::heartbeat);
        app.get("/workers/v1", workerHandler::list);

        app.get("/queues/v1", queueHandler::list);

        app.get("/health", ctx -> ctx.json(Map.of("status", "UP")));
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
            ctx.result(registry.scrape());
        });

        return app;
    }
}
