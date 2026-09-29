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
        @DefaultValue("100") int queueCapacity) {}
