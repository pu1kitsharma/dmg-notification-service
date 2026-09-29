package com.dmg.notify.notification;

import com.dmg.notify.common.Text;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_attempts")
public class DeliveryAttempt {
    static final int ERROR_MAX = 1000; // notification_attempts.error VARCHAR(1000)

    public enum Outcome { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String notificationId;
    private int attemptNo;
    @Enumerated(EnumType.STRING)
    private Outcome outcome;
    private String error;
    private Instant startedAt;
    private Instant finishedAt;

    protected DeliveryAttempt() {}

    public DeliveryAttempt(String notificationId, int attemptNo, Outcome outcome, String error,
                           Instant startedAt, Instant finishedAt) {
        this.notificationId = notificationId;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.error = Text.truncate(error, ERROR_MAX);
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public int getAttemptNo() { return attemptNo; }
    public Outcome getOutcome() { return outcome; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
