package com.dmg.notify.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds the first platform admin so the system is usable after a fresh start. */
@Component
public class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    static final String DEFAULT_PASSWORD = "admin12345"; // documented dev default in application.yml

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
            if (DEFAULT_PASSWORD.equals(password)) {
                log.warn("Seeded platform admin '{}' with the DEFAULT development password. Set APP_BOOTSTRAP_ADMIN_PASSWORD before any real use.", username);
            }
        }
    }
}
