package com.dmg.notify.template;

import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.security.TenantContext;
import com.dmg.notify.template.TemplateService.CreateTemplateRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {
    public record TemplateResponse(Long id, String name, ChannelType channel, int version, String subject,
                                   String body, Instant createdAt) {
        static TemplateResponse from(Template t) {
            return new TemplateResponse(t.getId(), t.getName(), t.getChannel(), t.getVersion(), t.getSubject(),
                    t.getBody(), t.getCreatedAt());
        }
    }

    private final TemplateService service;

    public TemplateController(TemplateService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody CreateTemplateRequest r) {
        return TemplateResponse.from(service.create(TenantContext.requireTenantId(), r));
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return service.list(TenantContext.requireTenantId()).stream().map(TemplateResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable long id) {
        return TemplateResponse.from(service.get(TenantContext.requireTenantId(), id));
    }
}
