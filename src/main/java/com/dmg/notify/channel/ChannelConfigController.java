package com.dmg.notify.channel;

import com.dmg.notify.common.ApiException;
import com.dmg.notify.security.TenantContext;
import com.dmg.notify.tenant.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Channels are enabled by default; a config row lets a tenant disable one or set a sender id. */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelConfigController {
    public record ChannelConfigDto(ChannelType channel, @NotNull Boolean enabled, @Size(max = 200) String senderId) {}

    private final ChannelConfigRepository repo;
    private final TenantRepository tenants;

    public ChannelConfigController(ChannelConfigRepository repo, TenantRepository tenants) {
        this.repo = repo;
        this.tenants = tenants;
    }

    @GetMapping
    public List<ChannelConfigDto> list() {
        return repo.findByTenantId(TenantContext.requireTenantId()).stream()
                .map(c -> new ChannelConfigDto(c.getChannel(), c.isEnabled(), c.getSenderId())).toList();
    }

    /**
     * Idempotent upsert. The first PUT of a channel is a read-then-insert, so concurrent PUTs are serialised per
     * tenant with a row lock (otherwise they collide on uq_channel_cfg and the losers get a 409).
     */
    @PutMapping("/{channel}")
    @Transactional
    public ChannelConfigDto put(@PathVariable ChannelType channel, @Valid @RequestBody ChannelConfigDto dto) {
        long tenantId = TenantContext.requireTenantId();
        tenants.lockById(tenantId).orElseThrow(() -> new ApiException.NotFound("Tenant not found"));
        ChannelConfig cfg = repo.findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> new ChannelConfig(tenantId, channel, dto.enabled(), dto.senderId()));
        cfg.update(dto.enabled(), dto.senderId());
        repo.save(cfg);
        return new ChannelConfigDto(channel, cfg.isEnabled(), cfg.getSenderId());
    }
}
