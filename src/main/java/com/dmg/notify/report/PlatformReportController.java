package com.dmg.notify.report;

import com.dmg.notify.common.ApiException;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.tenant.Tenant;
import com.dmg.notify.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cross-tenant view for platform admins (tenant admins only ever see their own data via /reports/delivery). */
@RestController
@RequestMapping("/api/v1/platform/reports")
public class PlatformReportController {
    public record TenantRow(long tenantId, String name, boolean active, long total, Double successRate,
                            Map<NotificationStatus, Long> byStatus) {}

    public record PlatformReport(Instant from, Instant to, long total, Double successRate,
                                 Map<NotificationStatus, Long> byStatus, List<TenantRow> tenants) {}

    private final NotificationRepository repo;
    private final TenantRepository tenants;
    private final Clock clock;

    public PlatformReportController(NotificationRepository repo, TenantRepository tenants, Clock clock) {
        this.repo = repo;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** Counts of notifications created in [from, to) across all tenants, by tenant and status. Defaults to the last 24h. */
    @GetMapping("/delivery")
    public PlatformReport delivery(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to != null ? to : clock.instant().plusSeconds(1);
        Instant start = from != null ? from : end.minus(24, ChronoUnit.HOURS);
        if (!start.isBefore(end)) throw new ApiException.BadRequest("'from' must be before 'to'");

        Map<Long, Map<NotificationStatus, Long>> perTenant = new HashMap<>();
        Map<NotificationStatus, Long> overall = new EnumMap<>(NotificationStatus.class);
        for (Object[] o : repo.countByTenantAndStatus(start, end)) {
            long tenantId = (Long) o[0];
            NotificationStatus status = (NotificationStatus) o[1];
            long count = (Long) o[2];
            perTenant.computeIfAbsent(tenantId, k -> new EnumMap<>(NotificationStatus.class)).merge(status, count, Long::sum);
            overall.merge(status, count, Long::sum);
        }
        Map<Long, Tenant> byId = tenants.findAllById(perTenant.keySet()).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
        List<TenantRow> rows = perTenant.entrySet().stream()
                .filter(e -> byId.containsKey(e.getKey()))
                .map(e -> {
                    Tenant t = byId.get(e.getKey());
                    return new TenantRow(t.getId(), t.getName(), t.isActive(),
                            e.getValue().values().stream().mapToLong(Long::longValue).sum(), successRate(e.getValue()), e.getValue());
                })
                .sorted((a, b) -> Long.compare(b.total(), a.total()))
                .toList();
        return new PlatformReport(start, end, overall.values().stream().mapToLong(Long::longValue).sum(),
                successRate(overall), overall, rows);
    }

    /** SENT / (SENT + DEAD); null while nothing has reached a final outcome. */
    static Double successRate(Map<NotificationStatus, Long> byStatus) {
        long sent = byStatus.getOrDefault(NotificationStatus.SENT, 0L);
        long dead = byStatus.getOrDefault(NotificationStatus.DEAD, 0L);
        return sent + dead == 0 ? null : (double) sent / (sent + dead);
    }
}
