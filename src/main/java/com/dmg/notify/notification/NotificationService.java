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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
        String hash = key == null ? null : fingerprint(req);
        if (key != null) {
            var existing = notifications.findByTenantIdAndIdempotencyKey(tenantId, key);
            if (existing.isPresent()) return replay(existing.get(), hash);
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
        n.setRequestHash(hash);
        try {
            tx.executeWithoutResult(s -> {
                notifications.saveAndFlush(n);
                state.recordCreated(n);
            });
            return new SubmitResult(n, true);
        } catch (DataIntegrityViolationException e) {
            if (key != null) { // lost a race with a concurrent submit carrying the same key
                return replay(notifications.findByTenantIdAndIdempotencyKey(tenantId, key).orElseThrow(), hash);
            }
            throw e;
        }
    }

    /**
     * Submits each item independently (own transaction): one bad item never fails the batch. Business errors
     * (unknown template, disabled channel, ...) are reported per item; the response order matches the request.
     */
    public BatchResponse submitBatch(long tenantId, BatchRequest req) {
        List<BatchItemResult> results = new ArrayList<>(req.items().size());
        int accepted = 0, duplicates = 0, rejected = 0;
        for (int i = 0; i < req.items().size(); i++) {
            BatchItem item = req.items().get(i);
            try {
                SubmitResult r = submit(tenantId, item.notification(), item.idempotencyKey());
                if (r.created()) {
                    accepted++;
                    results.add(new BatchItemResult(i, BatchOutcome.ACCEPTED, r.notification().getId(), 202, null));
                } else {
                    duplicates++;
                    results.add(new BatchItemResult(i, BatchOutcome.DUPLICATE, r.notification().getId(), 200, null));
                }
            } catch (ApiException e) {
                rejected++;
                results.add(new BatchItemResult(i, BatchOutcome.REJECTED, null, e.getStatus().value(), e.getMessage()));
            }
        }
        return new BatchResponse(accepted, duplicates, rejected, results);
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

    /** Tenant-scoped, newest first; every filter is optional. {@code from} inclusive, {@code to} exclusive (createdAt). */
    public Page<Notification> list(long tenantId, NotificationStatus status, ChannelType channel,
                                   Instant from, Instant to, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        if (from != null && to != null && !from.isBefore(to)) throw new ApiException.BadRequest("'from' must be before 'to'");
        Specification<Notification> spec = (root, q, cb) -> cb.equal(root.get("tenantId"), tenantId);
        if (status != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        if (channel != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("channel"), channel));
        if (from != null) spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
        if (to != null) spec = spec.and((root, q, cb) -> cb.lessThan(root.<Instant>get("createdAt"), to));
        return notifications.findAll(spec, pr);
    }

    public Notification cancel(long tenantId, String id) { return state.cancel(id, tenantId); }

    public Notification replay(long tenantId, String id) { return state.replay(id, tenantId); }

    /** Same key + same request = replay of the original; same key + different request = client bug, so 409. */
    private static SubmitResult replay(Notification existing, String hash) {
        if (existing.getRequestHash() != null && !existing.getRequestHash().equals(hash)) {
            throw new ApiException.Conflict("Idempotency-Key was already used with a different request");
        }
        return new SubmitResult(existing, false);
    }

    /** SHA-256 over a canonical form of everything that determines the notification (variables sorted by name). */
    static String fingerprint(SubmitRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.channel()).append('\u0000').append(req.templateName()).append('\u0000')
                .append(req.recipient()).append('\u0000').append(req.scheduledAt()).append('\u0000');
        if (req.variables() != null) {
            new TreeMap<>(req.variables()).forEach((k, v) -> sb.append(k).append('=').append(v).append('\u0000'));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM
        }
    }

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
