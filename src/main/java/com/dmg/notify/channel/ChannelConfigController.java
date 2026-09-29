package com.dmg.notify.channel;

import com.dmg.notify.security.TenantContext;
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

    public ChannelConfigController(ChannelConfigRepository repo) { this.repo = repo; }

    @GetMapping
    public List<ChannelConfigDto> list() {
        return repo.findByTenantId(TenantContext.requireTenantId()).stream()
                .map(c -> new ChannelConfigDto(c.getChannel(), c.isEnabled(), c.getSenderId())).toList();
    }

    @PutMapping("/{channel}")
    @Transactional
    public ChannelConfigDto put(@PathVariable ChannelType channel, @Valid @RequestBody ChannelConfigDto dto) {
        long tenantId = TenantContext.requireTenantId();
        ChannelConfig cfg = repo.findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> new ChannelConfig(tenantId, channel, dto.enabled(), dto.senderId()));
        cfg.update(dto.enabled(), dto.senderId());
        repo.save(cfg);
        return new ChannelConfigDto(channel, cfg.isEnabled(), cfg.getSenderId());
    }
}
