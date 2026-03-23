package io.github.nestorsokil.taskmaster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.nestorsokil.taskmaster.api.QueueHandler;
import io.github.nestorsokil.taskmaster.api.TaskHandler;
import io.github.nestorsokil.taskmaster.api.WorkerHandler;
import io.github.nestorsokil.taskmaster.config.TaskmasterConfig;
import io.github.nestorsokil.taskmaster.config.TaskmasterMetrics;
import io.github.nestorsokil.taskmaster.reaper.DeadlineReaper;
import io.github.nestorsokil.taskmaster.reaper.HeartbeatReaper;
import io.github.nestorsokil.taskmaster.reaper.RetentionReaper;
import io.github.nestorsokil.taskmaster.reaper.RunnableJob;
import io.github.nestorsokil.taskmaster.repository.TaskRepository;
import io.github.nestorsokil.taskmaster.repository.WorkerRepository;
import io.github.nestorsokil.taskmaster.service.ClaimService;
import io.github.nestorsokil.taskmaster.service.ObservabilityService;
import io.github.nestorsokil.taskmaster.service.TaskService;
import io.github.nestorsokil.taskmaster.service.WebhookService;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        start(TaskmasterConfig.load());
    }

    public static Javalin start(TaskmasterConfig config) throws Exception {
        var dataSource = buildDataSource(config.db());
        Scheduler scheduler = null;
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

            var app = buildJavalin(objectMapper, registry, taskHandler, workerHandler, queueHandler);
            scheduler = buildScheduler(heartbeatReaper, retentionReaper, deadlineReaper, config);

            var finalScheduler = scheduler;
            app.events(events -> {
                events.serverStopped(() -> shutdown(finalScheduler, dataSource));
                events.serverStartFailed(() -> shutdown(finalScheduler, dataSource));
            });

            app.start(8080);
            log.info("Taskmaster started");
            return app;
        } catch (Exception e) {
            shutdown(scheduler, dataSource);
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

    private static Javalin buildJavalin(
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

    private static Scheduler buildScheduler(
            HeartbeatReaper heartbeatReaper,
            RetentionReaper retentionReaper,
            DeadlineReaper deadlineReaper,
            TaskmasterConfig config) throws SchedulerException {

        var props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "taskmaster");
        props.setProperty("org.quartz.threadPool.threadCount", "3");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

        var scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduleJob(scheduler, "heartbeat", heartbeatReaper::reap, config.reaper().intervalMs());
        scheduleJob(scheduler, "retention", retentionReaper::reap, config.retention().intervalMs());
        scheduleJob(scheduler, "deadline", deadlineReaper::reap, 30_000L);
        scheduler.start();
        return scheduler;
    }

    private static void scheduleJob(Scheduler scheduler, String name, Runnable task, long intervalMs)
            throws SchedulerException {
        var jobData = new JobDataMap();
        jobData.put(RunnableJob.KEY, task);

        JobDetail detail = JobBuilder.newJob(RunnableJob.class)
                .withIdentity(name, "reapers")
                .usingJobData(jobData)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(name, "reapers")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(intervalMs)
                        .repeatForever())
                .startNow()
                .build();

        scheduler.scheduleJob(detail, trigger);
    }

    private static void shutdown(Scheduler scheduler, HikariDataSource dataSource) {
        if (scheduler != null) {
            try {
                scheduler.shutdown(true);
            } catch (SchedulerException e) {
                log.warn("Scheduler shutdown error", e);
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
