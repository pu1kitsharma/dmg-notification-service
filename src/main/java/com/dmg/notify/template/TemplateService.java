package com.dmg.notify.template;

import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.common.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {
    public record CreateTemplateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull ChannelType channel,
            @Size(max = 500) String subject,
            @NotBlank @Size(max = 4000) String body) {}

    private final TemplateRepository repo;
    private final Clock clock;

    public TemplateService(TemplateRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** Templates are immutable; "editing" creates the next version. */
    @Transactional
    public Template create(long tenantId, CreateTemplateRequest r) {
        int next = repo.findFirstByTenantIdAndNameAndChannelOrderByVersionDesc(tenantId, r.name(), r.channel())
                .map(t -> t.getVersion() + 1).orElse(1);
        return repo.save(new Template(tenantId, r.name(), r.channel(), next, r.subject(), r.body(), clock.instant()));
    }

    public Template latest(long tenantId, String name, ChannelType channel) {
        return repo.findFirstByTenantIdAndNameAndChannelOrderByVersionDesc(tenantId, name, channel)
                .orElseThrow(() -> new ApiException.NotFound("Template '" + name + "' for " + channel + " not found"));
    }

    public Template get(long tenantId, long id) {
        return repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException.NotFound("Template " + id + " not found"));
    }

    public List<Template> list(long tenantId) { return repo.findByTenantIdOrderByNameAscVersionDesc(tenantId); }
}
