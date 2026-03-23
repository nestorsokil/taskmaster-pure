# Taskmaster: Spring Removal Plan

Rewrite the service to drop all Spring dependencies. Keep the same REST API, database schema, and behavior.

## Technology Choices

| Concern | Spring (current) | Replacement |
|---|---|---|
| Web / REST | Spring MVC + Tomcat | **Javalin** (Jetty, virtual threads) |
| DI | `@Component`, `@Service`, auto-wiring | **Manual wiring** in `Main` |
| Database | Spring Data JDBC (`ListCrudRepository`, `@Query`) | **jOOQ** (codegen from Flyway schema) |
| Transactions | `@Transactional` | **jOOQ `DSLContext.transaction()`** |
| Scheduling | `@Scheduled` + `SchedulingConfigurer` | **Quartz Scheduler** (simple triggers) |
| Configuration | `@ConfigurationProperties` + `application.yml` | **Typesafe Config** (`application.conf` HOCON) |
| Metrics | Micrometer via Spring Boot Actuator + AOP `@Observed` | **Micrometer** standalone, explicit calls |
| Health / Prometheus | Actuator endpoints | **Javalin routes** (`/health`, `/prometheus`) |
| Validation | `spring-boot-starter-validation` + `@Valid` | **Manual** checks in handlers |
| DB migrations | Flyway (Spring-autoconfigured) | **Flyway** standalone (programmatic API) |
| JSON | Jackson (Spring-autoconfigured) | **Jackson** via Javalin plugin |
| Logging | SLF4J + Logback (unchanged) | **SLF4J + Logback** (unchanged) |
| Servlet filter (`CorrelationIdFilter`) | `OncePerRequestFilter` | **Javalin `before` handler** |
| Build | Maven (unchanged) | **Maven** (unchanged) |

## Step-by-step Plan

### 1. Add jOOQ (no codegen) -- DONE

- Add `org.jooq:jooq` dependency.
- No codegen — use jOOQ as a plain-SQL executor against the existing `DataSource`.
- Remove Spring Data JDBC dependency.

### 2. Replace configuration -- DONE

- Add `com.typesafe:config` dependency.
- Create `src/main/resources/application.conf` (HOCON) mirroring the current `application.yml` structure.
- Write a `TaskmasterConfig` record that loads and exposes typed config values (db url, heartbeat thresholds, reaper intervals, retry settings, webhook settings, etc.).
- Delete `TaskmasterProperties.java`, `application.yml`.

### 3. Replace web layer with Javalin -- DONE

- Add `io.javalin:javalin` + `io.javalin:javalin-jackson` dependencies.
- Rewrite controllers as plain classes whose methods take `io.javalin.http.Context`:
  - `TaskController` → `TaskHandler`
  - `WorkerController` → `WorkerHandler`
  - `QueueController` → `QueueHandler`
- Register routes in `Main`:
  ```java
  app.post("/tasks/v1", taskHandler::submit);
  app.get("/tasks/v1/{id}", taskHandler::get);
  // ...
  ```
- Replace `CorrelationIdFilter` with `app.before(ctx -> ...)`.
- Replace `ResponseStatusException` with Javalin's `ctx.status(...).json(...)` or exception mappers.
- Configure Jackson `ObjectMapper` (ISO dates, etc.) via `JavalinJackson`.
- Remove `spring-boot-starter-web`.

### 4. Replace repositories with jOOQ -- DONE

- Rewrite `TaskRepository` and `WorkerRepository` as plain classes taking `DSLContext`.
- Translate existing `@Query` SQL strings verbatim to `dsl.fetch(sql, params)` / `dsl.execute(sql, params)`.
- Map results to existing `Task` / `Worker` POJOs via `record.into(Task.class)` (reflection-based).
- Handle `Tags` (TEXT[]) and JSONB (`payload`) mapping with a custom `RecordMapper` or manual field extraction — no generated types needed.
- Remove `JdbcConfig` and `spring-boot-starter-data-jdbc`.

### 5. Replace transactions -- DONE

- Replace `@Transactional` with `dsl.transaction(cfg -> { ... })` at call sites in `ClaimService` and the reapers.
- jOOQ's transaction API integrates with its `DSLContext` — no external TX manager needed.

### 6. Replace scheduling with Quartz -- DONE

- Add `org.quartz-scheduler:quartz` dependency.
- Create three jobs implementing `org.quartz.Job`:
  - `DeadlineReaperJob` — runs every 30s (simple trigger, fixed delay).
  - `HeartbeatReaperJob` — interval from config.
  - `RetentionReaperJob` — interval from config.
- Initialize `StdSchedulerFactory` in `Main`, schedule all three jobs.
- Remove `SchedulingConfig`, `@EnableScheduling`, `@Scheduled` annotations.

### 7. Replace DI with manual wiring -- DONE

- `Main.main()` becomes the composition root:
  ```
  config       = TaskmasterConfig.load()
  dataSource   = HikariDataSource(config.db())
  flyway       = Flyway.configure()...migrate()
  dsl          = DSL.using(dataSource, SQLDialect.POSTGRES)
  taskRepo     = new TaskRepository(dsl)
  workerRepo   = new WorkerRepository(dsl)
  metrics      = new TaskmasterMetrics(meterRegistry)
  taskService  = new TaskService(taskRepo, metrics, config)
  claimService = new ClaimService(taskRepo, workerRepo, dsl, metrics)
  webhookService = new WebhookService(config, objectMapper)
  // ... handlers, scheduler, Javalin app
  ```
- All classes take their dependencies via constructor. Lombok `@RequiredArgsConstructor` still works.
- Delete `@Component`, `@Service`, `@Configuration`, `@Bean` annotations.

### 8. Replace metrics / observability endpoints -- DONE

- Keep `TaskmasterMetrics` as-is (it already uses Micrometer directly).
- Replace `@Observed` annotations with explicit `Observation.start()/stop()` or `Timer.record()` in `TaskService` and `ClaimService`.
- Add Javalin routes:
  - `GET /health` → `{"status":"UP"}` (+ optional DB ping).
  - `GET /prometheus` → `PrometheusMeterRegistry.scrape()`.
- Remove `spring-boot-starter-actuator`, `spring-boot-starter-aop`, `MicrometerConfig`.

### 9. Replace validation -- DONE

- Replace `@Valid` with explicit checks at the start of each handler method (null checks, range checks).
- Use Javalin's `ctx.bodyValidator<T>()` for simple validations, or throw `BadRequestResponse`.
- Remove `spring-boot-starter-validation`.

### 10. Clean up -- DONE

- Remove `spring-boot-starter-parent` from `pom.xml`. Manage dependency versions directly.
- Remove `spring-boot-maven-plugin`. Replace with `maven-shade-plugin` or `maven-assembly-plugin` to produce a fat JAR with `Main` as entry point.
- Remove all remaining Spring imports.
- Delete `TaskmasterApplication.java` (replaced by `Main`).
- Update `Dockerfile` (if any) — the app now starts with `java -jar taskmaster.jar`.

### 11. Migrate tests -- DONE

- Tests already use Rest-Assured + Awaitility (not Spring test), so the HTTP-level tests mostly survive.
- Replace test bootstrap: instead of Spring context, start the app programmatically in `@BeforeAll` (call `Main.start(config)` that returns the running Javalin instance).
- Remove `spring-boot-starter-test`.

## Dependency Summary (after)

```xml
<!-- Web -->
io.javalin:javalin
io.javalin:javalin-jackson

<!-- Database -->
org.jooq:jooq
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
org.postgresql:postgresql
com.zaxxer:HikariCP
<!-- No jooq-codegen-maven plugin needed -->

<!-- Scheduling -->
org.quartz-scheduler:quartz

<!-- Config -->
com.typesafe:config

<!-- Metrics -->
io.micrometer:micrometer-core
io.micrometer:micrometer-registry-prometheus

<!-- JSON -->
com.fasterxml.jackson.core:jackson-databind
com.fasterxml.jackson.datatype:jackson-datatype-jsr310

<!-- Logging -->
ch.qos.logback:logback-classic

<!-- Build -->
org.jooq:jooq-codegen-maven (plugin)
maven-shade-plugin (fat jar)

<!-- Test -->
io.rest-assured:rest-assured
org.awaitility:awaitility
org.testcontainers:postgresql
org.junit.jupiter:junit-jupiter
```

## Order of Execution

The steps above are written in dependency order. The most practical way to execute:

1. **Steps 1–2** (jOOQ codegen + config) — no runtime impact yet, just new files.
2. **Step 7** (manual wiring in `Main`) — create the composition root alongside Spring temporarily.
3. **Steps 3–6, 8–9** (replace web, repos, tx, scheduling, metrics, validation) — swap one subsystem at a time, verify tests pass after each.
4. **Steps 10–11** (clean up + migrate tests) — remove all Spring remnants last.
