package com.dmg.notify.notification;

import com.dmg.notify.channel.ChannelType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    private String id;
    private Long tenantId;
    @Enumerated(EnumType.STRING)
    private ChannelType channel;
    private Long templateId;
    private String recipient;
    private String subject;
    private String body;
    @Enumerated(EnumType.STRING)
    private NotificationStatus status;
    private String idempotencyKey;
    private Instant scheduledAt;
    private Instant nextAttemptAt;
    private int attemptCount;
    private int attemptBase;
    private Instant leaseUntil;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected Notification() {}

    public Notification(Long tenantId, ChannelType channel, Long templateId, String recipient, String subject,
                        String body, String idempotencyKey, Instant scheduledAt, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.channel = channel;
        this.templateId = templateId;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.idempotencyKey = idempotencyKey;
        this.scheduledAt = scheduledAt;
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = scheduledAt != null && scheduledAt.isAfter(now) ? scheduledAt : now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public ChannelType getChannel() { return channel; }
    public Long getTemplateId() { return templateId; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public NotificationStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getAttemptBase() { return attemptBase; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void setStatus(NotificationStatus s) { this.status = s; }
    void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public void setAttemptCount(int n) { this.attemptCount = n; }
    public void setAttemptBase(int n) { this.attemptBase = n; }
    public void setNextAttemptAt(Instant t) { this.nextAttemptAt = t; }
    public void setLeaseUntil(Instant t) { this.leaseUntil = t; }
    public void setLastError(String e) { this.lastError = e == null ? null : e.substring(0, Math.min(e.length(), 1000)); }
}
