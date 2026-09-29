package com.dmg.notify.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds the first platform admin so the system is usable after a fresh start. */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public BootstrapAdmin(AppUserRepository users, PasswordEncoder encoder,
                          @Value("${app.bootstrap.admin-username}") String username,
                          @Value("${app.bootstrap.admin-password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!users.existsByRole(Role.PLATFORM_ADMIN)) {
            users.save(new AppUser(username, encoder.encode(password), Role.PLATFORM_ADMIN, null));
        }
    }
}
