package com.dmg.notify.security;

import com.dmg.notify.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TenantContext {
    private TenantContext() {}

    /** Tenant of the calling tenant admin. Tenant-scoped endpoints derive tenancy from here only. */
    public static long requireTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal p && p.getTenantId() != null) {
            return p.getTenantId();
        }
        throw new ApiException.Forbidden("No tenant bound to caller");
    }
}
