package com.dmg.notify.channel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stand-in provider. Recipients containing "fail-transient" / "fail-permanent" simulate provider errors;
 * a repeated deliveryKey is acknowledged without re-sending (provider-side idempotency).
 */
public class SimulatedChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(SimulatedChannel.class);

    private final ChannelType type;
    private final long latencyMs;
    private final Set<String> delivered = ConcurrentHashMap.newKeySet();

    public SimulatedChannel(ChannelType type, long latencyMs) {
        this.type = type;
        this.latencyMs = latencyMs;
    }

    @Override public ChannelType type() { return type; }

    @Override
    public void send(DeliveryRequest r) {
        if (r.recipient().contains("fail-permanent")) throw new PermanentChannelException("recipient rejected by provider");
        if (r.recipient().contains("fail-transient")) throw new TransientChannelException("provider unavailable");
        if (!delivered.add(r.deliveryKey())) {
            log.debug("{} duplicate deliveryKey {} ignored", type, r.deliveryKey());
            return;
        }
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                delivered.remove(r.deliveryKey());
                throw new TransientChannelException("interrupted");
            }
        }
        log.info("[{}] delivered {} to {}", type, r.deliveryKey(), r.recipient());
    }
}
