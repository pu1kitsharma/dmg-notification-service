package com.dmg.notify.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.dispatch")
public record DispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("10") int batchPerTenant,
        @DefaultValue("60") long leaseSeconds,
        @DefaultValue("4") int poolSize,
        @DefaultValue("100") int queueCapacity,
        @DefaultValue("15000") long sendTimeoutMs) {

    /** The lease must outlive a send, otherwise a slow-but-healthy send would be reclaimed and re-sent. */
    public DispatchProperties {
        if (sendTimeoutMs < 1 || sendTimeoutMs >= leaseSeconds * 1000) {
            throw new IllegalArgumentException("app.dispatch.send-timeout-ms (" + sendTimeoutMs
                    + ") must be positive and shorter than app.dispatch.lease-seconds (" + leaseSeconds + "s)");
        }
    }
}
