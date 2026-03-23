package io.github.nestorsokil.taskmaster.config;

import com.typesafe.config.ConfigFactory;

import java.time.Duration;

public record TaskmasterConfig(
        Db db,
        Heartbeat heartbeat,
        Reaper reaper,
        Retry retry,
        Retention retention,
        Webhook webhook
) {

    public static TaskmasterConfig load() {
        var root       = ConfigFactory.load().getConfig("taskmaster");
        var dbConf     = root.getConfig("db");
        var hbConf     = root.getConfig("heartbeat");
        var reaperConf = root.getConfig("reaper");
        var retryConf  = root.getConfig("retry");
        var retConf    = root.getConfig("retention");
        var whConf     = root.getConfig("webhook");

        return new TaskmasterConfig(
                new Db(dbConf.getString("url"), dbConf.getString("username"), dbConf.getString("password")),
                new Heartbeat(hbConf.getLong("stale-threshold-seconds"), hbConf.getLong("dead-threshold-seconds")),
                new Reaper(reaperConf.getLong("interval-ms")),
                new Retry(retryConf.getDouble("base-delay-seconds")),
                new Retention(retConf.getDuration("ttl"), retConf.getLong("interval-ms"), retConf.getInt("batch-size")),
                new Webhook(whConf.getString("hmac-secret"), whConf.getInt("http-timeout-seconds"))
        );
    }

    public record Db(String url, String username, String password) {}

    public record Heartbeat(long staleThresholdSeconds, long deadThresholdSeconds) {}

    public record Reaper(long intervalMs) {}

    public record Retry(double baseDelaySeconds) {}

    public record Retention(Duration ttl, long intervalMs, int batchSize) {}

    public record Webhook(String hmacSecret, int httpTimeoutSeconds) {
        public Webhook {
            if (hmacSecret == null) hmacSecret = "";
            if (httpTimeoutSeconds <= 0) httpTimeoutSeconds = 10;
        }
    }
}
