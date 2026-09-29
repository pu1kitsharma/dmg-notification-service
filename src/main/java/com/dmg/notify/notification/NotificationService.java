package com.dmg.notify.notification;

import com.dmg.notify.channel.ChannelConfigRepository;
import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.common.ApiException;
import com.dmg.notify.notification.NotificationDtos.*;
import com.dmg.notify.template.Template;
import com.dmg.notify.template.TemplateRenderer;
import com.dmg.notify.template.TemplateService;
import com.dmg.notify.tenant.Tenant;
import com.dmg.notify.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
    public record SubmitResult(Notification notification, boolean created) {}

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final long MAX_SCHEDULE_DAYS = 365;

    private final NotificationRepository notifications;
    private final NotificationEventRepository events;
    private final DeliveryAttemptRepository attempts;
    private final NotificationStateMachine state;
    private final TemplateService templates;
    private final TenantRepository tenants;
    private final ChannelConfigRepository channelConfigs;
    private final TransactionTemplate tx;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, NotificationEventRepository events,
                               DeliveryAttemptRepository attempts, NotificationStateMachine state,
                               TemplateService templates, TenantRepository tenants,
                               ChannelConfigRepository channelConfigs, TransactionTemplate tx, Clock clock) {
        this.notifications = notifications;
        this.events = events;
        this.attempts = attempts;
        this.state = state;
        this.templates = templates;
        this.tenants = tenants;
        this.channelConfigs = channelConfigs;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Validates, renders and persists a PENDING notification (the DB row is the queue). The caller returns
     * immediately; delivery happens asynchronously in the dispatcher. An Idempotency-Key makes retried
     * submits return the original notification instead of creating a duplicate.
     */
    public SubmitResult submit(long tenantId, SubmitRequest req, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            var existing = notifications.findByTenantIdAndIdempotencyKey(tenantId, key);
            if (existing.isPresent()) return new SubmitResult(existing.get(), false);
        }
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new ApiException.NotFound("Tenant not found"));
        if (!tenant.isActive()) throw new ApiException.Forbidden("Tenant is deactivated");
        channelConfigs.findByTenantIdAndChannel(tenantId, req.channel()).ifPresent(c -> {
            if (!c.isEnabled()) throw new ApiException.Conflict("Channel " + req.channel() + " is disabled for this tenant");
        });
        validateRecipient(req.channel(), req.recipient());
        Instant now = clock.instant();
        if (req.scheduledAt() != null && req.scheduledAt().isAfter(now.plus(MAX_SCHEDULE_DAYS, ChronoUnit.DAYS))) {
            throw new ApiException.BadRequest("scheduledAt more than " + MAX_SCHEDULE_DAYS + " days ahead");
        }

        Template template = templates.latest(tenantId, req.templateName(), req.channel());
        String subject = TemplateRenderer.render(template.getSubject(), req.variables());
        String body = TemplateRenderer.render(template.getBody(), req.variables());
        Notification n = new Notification(tenantId, req.channel(), template.getId(), req.recipient(), subject, body,
                key, req.scheduledAt(), now);
        try {
            tx.executeWithoutResult(s -> {
                notifications.saveAndFlush(n);
                state.recordCreated(n);
            });
            return new SubmitResult(n, true);
        } catch (DataIntegrityViolationException e) {
            if (key != null) { // lost a race with a concurrent submit carrying the same key
                return new SubmitResult(notifications.findByTenantIdAndIdempotencyKey(tenantId, key).orElseThrow(), false);
            }
            throw e;
        }
    }

    public NotificationDetail detail(long tenantId, String id) {
        Notification n = notifications.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException.NotFound("Notification " + id + " not found"));
        List<EventResponse> ev = events.findByNotificationIdOrderByIdAsc(id).stream()
                .map(e -> new EventResponse(e.getFromStatus(), e.getToStatus(), e.getAttemptNo(), e.getReason(), e.getCreatedAt()))
                .toList();
        List<AttemptResponse> at = attempts.findByNotificationIdOrderByIdAsc(id).stream()
                .map(a -> new AttemptResponse(a.getAttemptNo(), a.getOutcome(), a.getError(), a.getStartedAt(), a.getFinishedAt()))
                .toList();
        return new NotificationDetail(NotificationResponse.from(n), ev, at);
    }

    public Page<Notification> list(long tenantId, NotificationStatus status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return status == null ? notifications.findByTenantId(tenantId, pr) : notifications.findByTenantIdAndStatus(tenantId, status, pr);
    }

    public Notification cancel(long tenantId, String id) { return state.cancel(id, tenantId); }

    public Notification replay(long tenantId, String id) { return state.replay(id, tenantId); }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) return null;
        if (key.length() > 100) throw new ApiException.BadRequest("Idempotency-Key longer than 100 characters");
        return key;
    }

    private static void validateRecipient(ChannelType channel, String recipient) {
        boolean ok = switch (channel) {
            case EMAIL -> EMAIL.matcher(recipient).matches();
            case SMS -> PHONE.matcher(recipient).matches();
            case PUSH, IN_APP -> !recipient.isBlank();
        };
        if (!ok) throw new ApiException.BadRequest("Invalid recipient for channel " + channel);
    }
}
