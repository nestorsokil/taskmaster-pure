package io.github.nestorsokil.taskmaster.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TaskmasterConfigTest {

    @Test
    void loadsKebabCaseConfigIntoRecords() {
        var config = TaskmasterConfig.load();

        assertThat(config.db().url()).isNotBlank();
        assertThat(config.heartbeat().staleThresholdSeconds()).isGreaterThan(0);
        assertThat(config.heartbeat().deadThresholdSeconds()).isGreaterThan(0);
        assertThat(config.reaper().intervalMs()).isGreaterThan(0);
        assertThat(config.retry().baseDelaySeconds()).isGreaterThan(0);
        assertThat(config.retention().ttl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.retention().intervalMs()).isGreaterThan(0);
        assertThat(config.retention().batchSize()).isGreaterThan(0);
        assertThat(config.webhook().hmacSecret()).isEqualTo("test-webhook-secret");
        assertThat(config.webhook().httpTimeoutSeconds()).isEqualTo(10);
    }
}
