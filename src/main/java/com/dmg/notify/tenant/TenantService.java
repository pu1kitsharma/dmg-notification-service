package com.dmg.notify.tenant;

import com.dmg.notify.common.ApiException;
import com.dmg.notify.security.AppUser;
import com.dmg.notify.security.AppUserRepository;
import com.dmg.notify.security.Role;
import com.dmg.notify.tenant.TenantDtos.*;
import java.time.Clock;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {
    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public TenantService(TenantRepository tenants, AppUserRepository users, PasswordEncoder encoder, Clock clock) {
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public Tenant create(CreateTenantRequest r) {
        if (tenants.existsByName(r.name())) throw new ApiException.Conflict("Tenant name already exists");
        if (users.existsByUsername(r.adminUsername())) throw new ApiException.Conflict("Username already exists");
        int rate = r.ratePerSecond() != null ? r.ratePerSecond() : 50;
        int burst = r.burst() != null ? r.burst() : rate * 2;
        int attempts = r.maxAttempts() != null ? r.maxAttempts() : 5;
        Tenant t = tenants.save(new Tenant(r.name(), rate, burst, attempts, clock.instant()));
        users.save(new AppUser(r.adminUsername(), encoder.encode(r.adminPassword()), Role.TENANT_ADMIN, t.getId()));
        return t;
    }

    @Transactional
    public Tenant update(long id, UpdateTenantRequest r) {
        Tenant t = get(id);
        if (r.active() != null) t.setActive(r.active());
        if (r.ratePerSecond() != null) t.setRatePerSecond(r.ratePerSecond());
        if (r.burst() != null) t.setBurst(r.burst());
        if (r.maxAttempts() != null) t.setMaxAttempts(r.maxAttempts());
        return t;
    }

    public Tenant get(long id) {
        return tenants.findById(id).orElseThrow(() -> new ApiException.NotFound("Tenant " + id + " not found"));
    }

    public List<Tenant> list() { return tenants.findAll(); }
}
