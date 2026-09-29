package com.dmg.notify.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.retry")
public record RetryProperties(
        @DefaultValue("2000") long baseDelayMs,
        @DefaultValue("300000") long maxDelayMs) {}
