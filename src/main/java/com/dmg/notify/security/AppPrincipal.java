package com.dmg.notify.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated user; carries the tenant binding so services never trust a client-supplied tenant id. */
public class AppPrincipal implements UserDetails {
    private final AppUser user;

    public AppPrincipal(AppUser user) { this.user = user; }

    public Long getTenantId() { return user.getTenantId(); }
    public Role getRole() { return user.getRole(); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getUsername(); }
}
