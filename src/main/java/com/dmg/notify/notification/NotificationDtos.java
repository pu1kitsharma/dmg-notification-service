package com.dmg.notify.notification;

import com.dmg.notify.channel.ChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record SubmitRequest(
            @NotNull ChannelType channel,
            @NotBlank @Size(max = 100) String templateName,
            @NotBlank @Size(max = 320) String recipient,
            Map<String, String> variables,
            Instant scheduledAt) {}

    public record NotificationResponse(String id, ChannelType channel, String recipient, NotificationStatus status,
                                       int attemptCount, Instant scheduledAt, Instant nextAttemptAt, String lastError,
                                       String subject, String body, Instant createdAt) {
        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getChannel(), n.getRecipient(), n.getStatus(),
                    n.getAttemptCount(), n.getScheduledAt(), n.getNextAttemptAt(), n.getLastError(),
                    n.getSubject(), n.getBody(), n.getCreatedAt());
        }
    }

    public record EventResponse(NotificationStatus from, NotificationStatus to, int attemptNo, String reason, Instant at) {}

    public record AttemptResponse(int attemptNo, DeliveryAttempt.Outcome outcome, String error, Instant startedAt,
                                  Instant finishedAt) {}

    public record NotificationDetail(NotificationResponse notification, List<EventResponse> events,
                                     List<AttemptResponse> attempts) {}
}
