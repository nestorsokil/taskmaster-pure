package io.github.nestorsokil.taskmaster.integration;

import io.github.nestorsokil.taskmaster.api.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.common.mapper.TypeRef;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

/**
 * Black-box HTTP client for the Taskmaster API.
 * Wraps all REST calls so tests read like plain English.
 * Uses the project's own DTO records for type-safe requests and responses.
 */
public final class TaskmasterClient {

    private static final Pattern PROMETHEUS_SAMPLE = Pattern.compile(
            "^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{([^}]*)})?\\s+([+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+)(?:[eE][+-]?\\d+)?)$");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final RestAssuredConfig CONFIG = RestAssuredConfig.config()
            .objectMapperConfig(ObjectMapperConfig.objectMapperConfig()
                    .jackson2ObjectMapperFactory((type, s) -> MAPPER));

    private final String baseUrl;

    public TaskmasterClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public TaskmasterClient() {
        this(System.getenv().getOrDefault("TASKMASTER_URL", "http://localhost:8080"));
    }

    // ---- task operations ----

    public SubmitTaskResponse submitTask(String queue, Object payload) {
        return submitTask(queue, payload, 0, null, null, null);
    }

    public SubmitTaskResponse submitTask(String queue, Object payload, int priority) {
        return submitTask(queue, payload, priority, null, null, null);
    }

    public SubmitTaskResponse submitTask(String queue, Object payload, int priority, Integer maxAttempts) {
        return submitTask(queue, payload, priority, maxAttempts, null, null);
    }

    public SubmitTaskResponse submitTask(String queue, Object payload, int priority, Integer maxAttempts, Instant deadline) {
        return submitTask(queue, payload, priority, maxAttempts, deadline, null);
    }

    public SubmitTaskResponse submitTask(String queue, Object payload, int priority, Integer maxAttempts, Instant deadline, List<String> tags) {
        return submitTask(queue, payload, priority, maxAttempts, deadline, tags, null);
    }

    public SubmitTaskResponse submitTask(String queue, Object payload, int priority, Integer maxAttempts, Instant deadline, List<String> tags, String callbackUrl) {
        var body = new SubmitTaskRequest(queue, MAPPER.valueToTree(payload), priority, maxAttempts, deadline, tags, callbackUrl);
        return request()
                .body(body)
                .when().post("/tasks/v1")
                .then().statusCode(202)
                .extract().as(SubmitTaskResponse.class);
    }

    public SubmitTaskResponse submitTask(String queue, Object payload, List<String> tags) {
        return submitTask(queue, payload, 0, null, null, tags);
    }

    public Response submitTaskRaw(Map<String, Object> body) {
        return request().body(body).when().post("/tasks/v1");
    }

    public TaskResponse getTask(UUID taskId) {
        return request()
                .when().get("/tasks/v1/{id}", taskId)
                .then().statusCode(200)
                .extract().as(TaskResponse.class);
    }

    public Response getTaskRaw(UUID taskId) {
        return request().when().get("/tasks/v1/{id}", taskId);
    }

    public List<TaskResponse> listTasks(String queue, String status, Integer limit) {
        var spec = request();
        if (queue != null) spec = spec.queryParam("queue", queue);
        if (status != null) spec = spec.queryParam("status", status);
        if (limit != null) spec = spec.queryParam("limit", limit);
        return spec
                .when().get("/tasks/v1")
                .then().statusCode(200)
                .extract().as(new TypeRef<>() {});
    }

    // ---- claim operations ----

    public ClaimTasksResponse claimTasks(String workerId, String queue, int maxTasks) {
        var body = new ClaimTasksRequest(workerId, queue, maxTasks);
        return request()
                .body(body)
                .when().post("/tasks/v1/claim")
                .then().statusCode(200)
                .extract().as(ClaimTasksResponse.class);
    }

    public Response claimTasksRaw(Map<String, Object> body) {
        return request().body(body).when().post("/tasks/v1/claim");
    }

    // ---- complete / fail ----

    public void completeTask(UUID taskId, String workerId, String result) {
        var body = new CompleteTaskRequest(workerId, result);
        request().body(body)
                .when().post("/tasks/v1/{id}/complete", taskId)
                .then().statusCode(204);
    }

    public Response completeTaskRaw(UUID taskId, Map<String, Object> body) {
        return request().body(body).when().post("/tasks/v1/{id}/complete", taskId);
    }

    public void failTask(UUID taskId, String workerId, String error) {
        var body = new FailTaskRequest(workerId, error);
        request().body(body)
                .when().post("/tasks/v1/{id}/fail", taskId)
                .then().statusCode(204);
    }

    public Response failTaskRaw(UUID taskId, Map<String, Object> body) {
        return request().body(body).when().post("/tasks/v1/{id}/fail", taskId);
    }

    // ---- worker operations ----

    public void registerWorker(String workerId, String queue) {
        registerWorker(workerId, queue, null);
    }

    public void registerWorker(String workerId, String queue, List<String> tags) {
        var body = new RegisterWorkerRequest(workerId, queue, null, tags);
        request().body(body)
                .when().post("/workers/v1/register")
                .then().statusCode(200);
    }

    public void heartbeat(String workerId) {
        request()
                .when().post("/workers/v1/{id}/heartbeat", workerId)
                .then().statusCode(204);
    }

    public Response heartbeatRaw(String workerId) {
        return request().when().post("/workers/v1/{id}/heartbeat", workerId);
    }

    public List<WorkerResponse> listWorkers() {
        return request()
                .when().get("/workers/v1")
                .then().statusCode(200)
                .extract().as(new TypeRef<>() {});
    }

    // ---- queue stats ----

    public List<QueueSummaryResponse> getQueueStats() {
        return request()
                .when().get("/queues/v1")
                .then().statusCode(200)
                .extract().as(new TypeRef<>() {});
    }

    // ---- metrics (Prometheus) ----

    /**
     * Returns the total value of a Micrometer counter from the Prometheus scrape.
     * Optionally filters by a tag (e.g. {@code "queue", "my-queue"}).
     *
     * @return the counter value, or 0.0 if the metric does not exist yet
     */
    public double getMetric(String name, String... tags) {
        return scrapeSamples().stream()
                .filter(sample -> sample.metricName().equals(prometheusCounterName(name)))
                .filter(sample -> matchesTags(sample, tags))
                .mapToDouble(sample -> sample.value())
                .findFirst()
                .orElse(0.0);
    }

    /**
     * Returns a specific statistic (e.g. "COUNT", "TOTAL_TIME") from a timer metric.
     * Falls back to 0.0 if the metric does not exist yet.
     */
    public double getMetricStatistic(String name, String statistic, String... tags) {
        String targetName = switch (statistic) {
            case "COUNT" -> prometheusTimerCountName(name);
            case "TOTAL_TIME" -> prometheusTimerSumName(name);
            default -> throw new IllegalArgumentException("Unsupported statistic: " + statistic);
        };
        return scrapeSamples().stream()
                .filter(sample -> sample.metricName().equals(targetName))
                .filter(sample -> matchesTags(sample, tags))
                .mapToDouble(sample -> sample.value())
                .findFirst()
                .orElse(0.0);
    }

    // ---- internal helpers ----

    private List<Sample> scrapeSamples() {
        var body = request()
                .when().get("/metrics")
                .then().statusCode(200)
                .extract().asString();

        var samples = new java.util.ArrayList<Sample>();
        for (String line : body.split("\\R")) {
            var sample = parseSample(line);
            if (sample != null) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private Sample parseSample(String line) {
        if (line.isBlank() || line.startsWith("#")) {
            return null;
        }
        var matcher = PROMETHEUS_SAMPLE.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new Sample(matcher.group(1), parseLabels(matcher.group(2)), Double.parseDouble(matcher.group(3)));
    }

    private Map<String, String> parseLabels(String labels) {
        var result = new LinkedHashMap<String, String>();
        if (labels == null || labels.isBlank()) {
            return result;
        }
        for (String part : labels.split(",")) {
            var idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            var key = part.substring(0, idx);
            var value = part.substring(idx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
        }
        return result;
    }

    private boolean matchesTags(Sample sample, String... tags) {
        if (tags.length == 0) {
            return true;
        }
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("Tags must be key/value pairs");
        }
        for (int i = 0; i < tags.length; i += 2) {
            var expectedValue = tags[i + 1];
            if (!expectedValue.equals(sample.labels().get(tags[i]))) {
                return false;
            }
        }
        return true;
    }

    private static String prometheusCounterName(String name) {
        return sanitizeMetricName(name) + "_total";
    }

    private static String prometheusTimerCountName(String name) {
        return sanitizeMetricName(name) + "_seconds_count";
    }

    private static String prometheusTimerSumName(String name) {
        return sanitizeMetricName(name) + "_seconds_sum";
    }

    private static String sanitizeMetricName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private RequestSpecification request() {
        return given()
                .config(CONFIG)
                .baseUri(baseUrl)
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON);
    }

    private record Sample(String metricName, Map<String, String> labels, double value) {}
}
