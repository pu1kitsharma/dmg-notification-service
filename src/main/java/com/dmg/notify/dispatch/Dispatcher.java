package com.dmg.notify.dispatch;

import com.dmg.notify.channel.ChannelConfig;
import com.dmg.notify.channel.ChannelConfigRepository;
import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationStateMachine;
import com.dmg.notify.ratelimit.RateLimitService;
import com.dmg.notify.tenant.GlobalLimit;
import com.dmg.notify.tenant.GlobalLimitRepository;
import com.dmg.notify.tenant.Tenant;
import com.dmg.notify.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * One poll cycle: reclaim expired leases, then for each tenant with due work claim at most
 * {@code batchPerTenant} notifications (fairness), respecting rate limits, and hand them to bounded channel pools.
 * Safe to run concurrently from several threads/instances: claims are atomic compare-and-set.
 */
@Service
public class Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final NotificationRepository notifications;
    private final NotificationStateMachine state;
    private final TenantRepository tenants;
    private final GlobalLimitRepository globalLimits;
    private final RateLimitService rateLimits;
    private final ChannelExecutors executors;
    private final DeliveryWorker worker;
    private final DeliveryOutcomeService outcomes;
    private final ChannelConfigRepository channelConfigs;
    private final DispatchProperties props;
    private final Clock clock;
    private final AtomicInteger cursor = new AtomicInteger();

    public Dispatcher(NotificationRepository notifications, NotificationStateMachine state, TenantRepository tenants,
                      GlobalLimitRepository globalLimits, RateLimitService rateLimits, ChannelExecutors executors,
                      DeliveryWorker worker, DeliveryOutcomeService outcomes, ChannelConfigRepository channelConfigs,
                      DispatchProperties props, Clock clock) {
        this.notifications = notifications;
        this.state = state;
        this.tenants = tenants;
        this.globalLimits = globalLimits;
        this.rateLimits = rateLimits;
        this.executors = executors;
        this.worker = worker;
        this.outcomes = outcomes;
        this.channelConfigs = channelConfigs;
        this.props = props;
        this.clock = clock;
    }

    /** @return number of notifications handed to workers in this cycle */
    public int runOnce() {
        Instant now = clock.instant();
        reclaimExpiredLeases(now);

        List<Long> tenantIds = new ArrayList<>(notifications.tenantsWithDue(now));
        if (tenantIds.isEmpty()) return 0;
        Collections.sort(tenantIds);
        Collections.rotate(tenantIds, -(Math.floorMod(cursor.getAndIncrement(), tenantIds.size())));

        Map<Long, Tenant> tenantById = tenants.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
        GlobalLimit global = globalLimits.findById(GlobalLimit.ID).orElseThrow();

        int dispatched = 0;
        Set<ChannelType> saturated = EnumSet.noneOf(ChannelType.class); // pools are shared by all tenants
        for (Long tenantId : tenantIds) {
            Tenant tenant = tenantById.get(tenantId);
            if (tenant == null || !tenant.isActive()) continue;
            // rows of a channel the tenant admin disabled stay PENDING (resume on re-enable) and must not
            // occupy the batch, so they are excluded in the query instead of skipped afterwards
            // a saturated channel pool is excluded the same way, so it cannot block the tenant's other channels
            List<ChannelType> enabled = enabledChannels(tenantId).stream().filter(c -> !saturated.contains(c)).toList();
            if (enabled.isEmpty()) continue;
            List<Notification> due = notifications.findDue(tenantId, enabled, now, PageRequest.of(0, props.batchPerTenant()));
            for (Notification n : due) {
                if (saturated.contains(n.getChannel())) continue; // saturated earlier in this batch
                if (!rateLimits.tryAcquire(tenantId, tenant.getRatePerSecond(), tenant.getBurst(),
                        global.getRatePerSecond(), global.getBurst())) {
                    break; // stays PENDING, picked up in a later cycle once tokens refill
                }
                var attempt = state.claim(n.getId(), now, now.plusSeconds(props.leaseSeconds()));
                if (attempt.isEmpty()) { // another dispatcher won it; the permits are unused
                    rateLimits.refund(tenantId);
                    continue;
                }
                String id = n.getId();
                int attemptNo = attempt.get();
                if (executors.trySubmit(n.getChannel(), () -> worker.deliver(id, attemptNo))) {
                    dispatched++;
                } else {
                    state.release(id, "worker pool saturated; released");
                    rateLimits.refund(tenantId);
                    saturated.add(n.getChannel()); // back off this channel only; other channels keep flowing
                }
            }
        }
        return dispatched;
    }

    private List<ChannelType> enabledChannels(long tenantId) {
        Set<ChannelType> disabled = EnumSet.noneOf(ChannelType.class);
        for (ChannelConfig c : channelConfigs.findByTenantId(tenantId)) if (!c.isEnabled()) disabled.add(c.getChannel());
        return Arrays.stream(ChannelType.values()).filter(t -> !disabled.contains(t)).toList();
    }

    private void reclaimExpiredLeases(Instant now) {
        for (Notification n : notifications.findExpiredLeases(now)) {
            log.warn("Lease expired for {} attempt {}; treating as transient failure", n.getId(), n.getAttemptCount());
            outcomes.recordFailure(n.getId(), n.getAttemptCount(), "lease expired (worker did not report)", false, n.getUpdatedAt());
        }
    }
}
