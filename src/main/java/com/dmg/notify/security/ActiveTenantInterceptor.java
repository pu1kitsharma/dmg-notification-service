package com.dmg.notify.security;

import com.dmg.notify.common.ApiException;
import com.dmg.notify.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A deactivated tenant is read-only: its admin can still look at notifications, reports and templates, but every
 * mutating call (send, cancel, replay, batch, templates, channel config) is rejected with 403. Platform admins
 * are not tenant-bound and are unaffected.
 */
@Component
public class ActiveTenantInterceptor implements HandlerInterceptor {
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final TenantRepository tenants;

    public ActiveTenantInterceptor(TenantRepository tenants) { this.tenants = tenants; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READ_ONLY_METHODS.contains(request.getMethod())) return true;
        if (request.getRequestURI().endsWith("/preview")) return true; // dry run, changes nothing
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal p && p.getTenantId() != null) {
            boolean active = tenants.findById(p.getTenantId()).map(t -> t.isActive()).orElse(false);
            if (!active) throw new ApiException.Forbidden("Tenant is deactivated (read-only)");
        }
        return true;
    }
}
