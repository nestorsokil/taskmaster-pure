package io.github.nestorsokil.taskmaster.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.typesafe.config.ConfigFactory;

import java.io.IOException;
import java.time.Duration;

public record TaskmasterConfig(
        Db db,
        Heartbeat heartbeat,
        Reaper reaper,
        Retry retry,
        Retention retention,
        Webhook webhook
) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule().addDeserializer(Duration.class, new HoconDurationDeserializer()));

    public static TaskmasterConfig load() {
        return MAPPER.convertValue(ConfigFactory
            .load().getConfig("taskmaster")
            .root().unwrapped(), 
            TaskmasterConfig.class);
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

    private static final class HoconDurationDeserializer extends StdDeserializer<Duration> {
        private HoconDurationDeserializer() {
            super(Duration.class);
        }

        @Override
        public Duration deserialize(JsonParser p, DeserializationContext ctxt)throws IOException {
            return ConfigFactory.parseString("ttl = " + p.getValueAsString()).getDuration("ttl");
        }
    }
}
