package com.devlink.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 작업 알림 발행기의 점유·재시도 정책이다. FAILED는 자동 재처리하지 않는다. */
@Component
public record OutboxPublisherSettings(boolean enabled, int batchSize, long leaseMillis,
        long retryBaseMillis, long retryMaxMillis, int maxAttempts) {
    public OutboxPublisherSettings(
            @Value("${devlink.outbox.publisher-enabled:true}") boolean enabled,
            @Value("${devlink.outbox.batch-size:20}") int batchSize,
            @Value("${devlink.outbox.lease-ms:30000}") long leaseMillis,
            @Value("${devlink.outbox.retry-base-ms:1000}") long retryBaseMillis,
            @Value("${devlink.outbox.retry-max-ms:60000}") long retryMaxMillis,
            @Value("${devlink.outbox.max-attempts:5}") int maxAttempts) {
        Assert.isTrue(batchSize > 0 && batchSize <= 100, "batch size must be 1..100");
        Assert.isTrue(leaseMillis > 0 && retryBaseMillis > 0 && retryMaxMillis >= retryBaseMillis,
                "lease and retry intervals must be positive");
        Assert.isTrue(maxAttempts > 0 && maxAttempts <= 100, "max attempts must be 1..100");
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseMillis = leaseMillis;
        this.retryBaseMillis = retryBaseMillis;
        this.retryMaxMillis = retryMaxMillis;
        this.maxAttempts = maxAttempts;
    }

    public long retryDelayMillis(int attempt) {
        long delay = retryBaseMillis;
        for (int index = 1; index < attempt && delay < retryMaxMillis; index++) {
            delay = delay >= retryMaxMillis - delay ? retryMaxMillis : delay * 2;
        }
        return delay;
    }
}
