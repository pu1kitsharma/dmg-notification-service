package com.dmg.notify.tenant;

import com.dmg.notify.tenant.TenantDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Platform-admin only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService service;

    public TenantController(TenantService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest r) {
        return TenantResponse.from(service.create(r));
    }

    @GetMapping
    public List<TenantResponse> list() {
        return service.list().stream().map(TenantResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable long id) { return TenantResponse.from(service.get(id)); }

    @PatchMapping("/{id}")
    public TenantResponse update(@PathVariable long id, @Valid @RequestBody UpdateTenantRequest r) {
        return TenantResponse.from(service.update(id, r));
    }
}
