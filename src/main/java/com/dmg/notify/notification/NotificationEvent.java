package com.dmg.notify.notification;

import com.dmg.notify.common.Text;
import jakarta.persistence.*;
import java.time.Instant;

/** Append-only audit row for every state transition. */
@Entity
@Table(name = "notification_events")
public class NotificationEvent {
    static final int REASON_MAX = 500; // notification_events.reason VARCHAR(500)

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String notificationId;
    private Long tenantId;
    @Enumerated(EnumType.STRING)
    private NotificationStatus fromStatus;
    @Enumerated(EnumType.STRING)
    private NotificationStatus toStatus;
    private int attemptNo;
    private String reason;
    private Instant createdAt;

    protected NotificationEvent() {}

    public NotificationEvent(String notificationId, Long tenantId, NotificationStatus from, NotificationStatus to,
                             int attemptNo, String reason, Instant createdAt) {
        this.notificationId = notificationId;
        this.tenantId = tenantId;
        this.fromStatus = from;
        this.toStatus = to;
        this.attemptNo = attemptNo;
        this.reason = Text.truncate(reason, REASON_MAX);
        this.createdAt = createdAt;
    }

    public NotificationStatus getFromStatus() { return fromStatus; }
    public NotificationStatus getToStatus() { return toStatus; }
    public int getAttemptNo() { return attemptNo; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
