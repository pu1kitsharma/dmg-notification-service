package com.dmg.notify.report;

import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.common.ApiException;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.security.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class DeliveryReportController {
    public record Row(ChannelType channel, NotificationStatus status, long count) {}

    public record TemplateRow(String template, Map<NotificationStatus, Long> byStatus) {}

    public record FailureRow(String reason, long count) {}

    /** successRate = SENT / (SENT + DEAD), i.e. of the notifications that reached a final outcome; null if none did. */
    public record DeliveryReport(Instant from, Instant to, long total, Double successRate,
                                 Map<NotificationStatus, Long> byStatus, List<Row> rows,
                                 List<TemplateRow> byTemplate, List<FailureRow> topFailures) {}

    private static final int TOP_FAILURES = 5;

    private final NotificationRepository repo;
    private final Clock clock;

    public DeliveryReportController(NotificationRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** Counts of the tenant's notifications created in [from, to), by channel and status. Defaults to the last 24h. */
    @GetMapping("/delivery")
    public DeliveryReport delivery(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                   @RequestParam(required = false) ChannelType channel) {
        Instant end = to != null ? to : clock.instant().plusSeconds(1);
        Instant start = from != null ? from : end.minus(24, ChronoUnit.HOURS);
        if (!start.isBefore(end)) throw new ApiException.BadRequest("'from' must be before 'to'");
        long tenantId = TenantContext.requireTenantId();
        List<ChannelType> channels = channel == null ? List.of(ChannelType.values()) : List.of(channel);
        List<Row> rows = repo.countByChannelAndStatus(tenantId, start, end).stream()
                .map(o -> new Row((ChannelType) o[0], (NotificationStatus) o[1], (Long) o[2]))
                .filter(r -> channel == null || r.channel() == channel)
                .toList();
        Map<NotificationStatus, Long> byStatus = new EnumMap<>(NotificationStatus.class);
        rows.forEach(r -> byStatus.merge(r.status(), r.count(), Long::sum));
        long sent = byStatus.getOrDefault(NotificationStatus.SENT, 0L);
        long dead = byStatus.getOrDefault(NotificationStatus.DEAD, 0L);
        Double successRate = sent + dead == 0 ? null : (double) sent / (sent + dead);

        Map<String, Map<NotificationStatus, Long>> tpl = new TreeMap<>();
        for (Object[] o : repo.countByTemplateAndStatus(tenantId, channels, start, end)) {
            tpl.computeIfAbsent((String) o[0], k -> new EnumMap<>(NotificationStatus.class))
                    .merge((NotificationStatus) o[1], (Long) o[2], Long::sum);
        }
        List<TemplateRow> byTemplate = tpl.entrySet().stream().map(e -> new TemplateRow(e.getKey(), e.getValue())).toList();
        List<FailureRow> topFailures = repo.topDeadReasons(tenantId, channels, start, end, PageRequest.of(0, TOP_FAILURES)).stream()
                .map(o -> new FailureRow(o[0] == null ? "unknown" : (String) o[0], (Long) o[1])).toList();
        return new DeliveryReport(start, end, rows.stream().mapToLong(Row::count).sum(), successRate, byStatus, rows,
                byTemplate, topFailures);
    }
}
