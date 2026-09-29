package com.dmg.notify.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);
    private final Dispatcher dispatcher;

    public DispatchScheduler(Dispatcher dispatcher) { this.dispatcher = dispatcher; }

    @Scheduled(fixedDelayString = "${app.dispatch.poll-interval-ms:500}")
    void poll() {
        try {
            dispatcher.runOnce();
        } catch (RuntimeException e) {
            log.error("Dispatch cycle failed", e);
        }
    }
}
