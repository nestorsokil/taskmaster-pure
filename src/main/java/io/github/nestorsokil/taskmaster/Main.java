package io.github.nestorsokil.taskmaster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.nestorsokil.taskmaster.api.ApiServer;
import io.github.nestorsokil.taskmaster.api.QueueHandler;
import io.github.nestorsokil.taskmaster.api.TaskHandler;
import io.github.nestorsokil.taskmaster.api.WorkerHandler;
import io.github.nestorsokil.taskmaster.config.TaskmasterConfig;
import io.github.nestorsokil.taskmaster.config.TaskmasterMetrics;
import io.github.nestorsokil.taskmaster.reaper.DeadlineReaper;
import io.github.nestorsokil.taskmaster.reaper.HeartbeatReaper;
import io.github.nestorsokil.taskmaster.reaper.RetentionReaper;
import io.github.nestorsokil.taskmaster.reaper.ReaperScheduler;
import io.github.nestorsokil.taskmaster.repository.TaskRepository;
import io.github.nestorsokil.taskmaster.repository.WorkerRepository;
import io.github.nestorsokil.taskmaster.service.ClaimService;
import io.github.nestorsokil.taskmaster.service.ObservabilityService;
import io.github.nestorsokil.taskmaster.service.TaskService;
import io.github.nestorsokil.taskmaster.service.WebhookService;
import io.javalin.Javalin;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        start(TaskmasterConfig.load());
    }

    public static Javalin start(TaskmasterConfig config) throws Exception {
        var dataSource = buildDataSource(config.db());
        ReaperScheduler reaperScheduler = null;
        try {
            Flyway.configure().dataSource(dataSource).load().migrate();

            var dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
            var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            var objectMapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            var taskRepo = new TaskRepository(dsl);
            var workerRepo = new WorkerRepository(dsl);
            var metrics = new TaskmasterMetrics(registry);
            var webhookSvc = new WebhookService(objectMapper, config, metrics);
            var taskSvc = new TaskService(taskRepo, metrics, config, webhookSvc);
            var claimSvc = new ClaimService(dsl, taskRepo, workerRepo, metrics);
            var observability = new ObservabilityService(taskRepo, workerRepo);

            var taskHandler = new TaskHandler(taskSvc, claimSvc, observability, objectMapper);
            var workerHandler = new WorkerHandler(workerRepo, observability, metrics);
            var queueHandler = new QueueHandler(observability);

            var heartbeatReaper = new HeartbeatReaper(dsl, workerRepo, taskRepo, config, metrics, webhookSvc);
            var retentionReaper = new RetentionReaper(taskRepo, workerRepo, config, metrics);
            var deadlineReaper = new DeadlineReaper(taskRepo, metrics, webhookSvc);

            var app = ApiServer.create(objectMapper, registry, taskHandler, workerHandler, queueHandler);
            reaperScheduler = ReaperScheduler.start(heartbeatReaper, retentionReaper, deadlineReaper, config);

            var finalReaperScheduler = reaperScheduler;
            app.events(events -> {
                events.serverStopped(() -> shutdown(finalReaperScheduler, dataSource));
                events.serverStartFailed(() -> shutdown(finalReaperScheduler, dataSource));
            });

            app.start(8080);
            log.info("Taskmaster started");
            return app;
        } catch (Exception e) {
            shutdown(reaperScheduler, dataSource);
            throw e;
        }
    }

    private static HikariDataSource buildDataSource(TaskmasterConfig.Db db) {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(db.url());
        cfg.setUsername(db.username());
        cfg.setPassword(db.password());
        return new HikariDataSource(cfg);
    }

    private static void shutdown(ReaperScheduler reaperScheduler, HikariDataSource dataSource) {
        if (reaperScheduler != null) {
            try {
                reaperScheduler.close();
            } catch (Exception e) {
                log.warn("Reaper scheduler shutdown error", e);
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
