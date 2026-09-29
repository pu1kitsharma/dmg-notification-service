package com.dmg.notify.template;

import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.security.TenantContext;
import com.dmg.notify.template.TemplateService.CreateTemplateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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

    public record PreviewRequest(Map<String, String> variables) {}

    public record DraftPreviewRequest(@Size(max = 500) String subject, @NotBlank @Size(max = 4000) String body,
                                      Map<String, String> variables) {}

    /**
     * Dry run: what a submit would render, without creating anything. Missing variables are a 422 exactly as on a real
     * submit. {@code unusedVariables} are supplied but not referenced (usually a typo); {@code withinLimits} says
     * whether the rendered text fits the notification columns (500 / 4000), which a real submit enforces with a 422.
     */
    public record PreviewResponse(String subject, String body, int subjectLength, int bodyLength,
                                  List<String> variablesUsed, List<String> unusedVariables, boolean withinLimits) {}

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

    /** Preview a draft (not yet saved) subject/body with sample variables. */
    @PostMapping("/preview")
    public PreviewResponse previewDraft(@Valid @RequestBody DraftPreviewRequest r) {
        return preview(r.subject(), r.body(), r.variables());
    }

    /** Preview an existing template version with sample variables. */
    @PostMapping("/{id}/preview")
    public PreviewResponse previewSaved(@PathVariable long id, @RequestBody(required = false) PreviewRequest r) {
        Template t = service.get(TenantContext.requireTenantId(), id);
        return preview(t.getSubject(), t.getBody(), r == null ? null : r.variables());
    }

    private static PreviewResponse preview(String subjectTemplate, String bodyTemplate, Map<String, String> vars) {
        Set<String> used = new LinkedHashSet<>(TemplateRenderer.variables(subjectTemplate));
        used.addAll(TemplateRenderer.variables(bodyTemplate));
        String subject = TemplateRenderer.render(subjectTemplate, vars);
        String body = TemplateRenderer.render(bodyTemplate, vars);
        List<String> unused = vars == null ? List.of() : vars.keySet().stream().filter(k -> !used.contains(k)).sorted().toList();
        int subjectLength = subject == null ? 0 : subject.length();
        return new PreviewResponse(subject, body, subjectLength, body.length(), List.copyOf(used), unused,
                subjectLength <= TemplateRenderer.MAX_SUBJECT && body.length() <= TemplateRenderer.MAX_BODY);
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable long id) {
        return TemplateResponse.from(service.get(TenantContext.requireTenantId(), id));
    }
}
