package com.dmg.notify.channel;

/**
 * A delivery provider. Implementations MUST be idempotent on {@link DeliveryRequest#deliveryKey()}:
 * if a send succeeded but we crashed before recording it, the retry reuses the same key.
 */
public interface Channel {
    ChannelType type();

    void send(DeliveryRequest request);

    record DeliveryRequest(String deliveryKey, long tenantId, String recipient, String subject, String body) {}

    class TransientChannelException extends RuntimeException {
        public TransientChannelException(String message) { super(message); }
    }

    class PermanentChannelException extends RuntimeException {
        public PermanentChannelException(String message) { super(message); }
    }
}
