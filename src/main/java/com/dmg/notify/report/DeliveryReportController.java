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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class DeliveryReportController {
    public record Row(ChannelType channel, NotificationStatus status, long count) {}

    public record DeliveryReport(Instant from, Instant to, long total, Map<NotificationStatus, Long> byStatus, List<Row> rows) {}

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
        List<Row> rows = repo.countByChannelAndStatus(TenantContext.requireTenantId(), start, end).stream()
                .map(o -> new Row((ChannelType) o[0], (NotificationStatus) o[1], (Long) o[2]))
                .filter(r -> channel == null || r.channel() == channel)
                .toList();
        Map<NotificationStatus, Long> byStatus = new EnumMap<>(NotificationStatus.class);
        rows.forEach(r -> byStatus.merge(r.status(), r.count(), Long::sum));
        return new DeliveryReport(start, end, rows.stream().mapToLong(Row::count).sum(), byStatus, rows);
    }
}
