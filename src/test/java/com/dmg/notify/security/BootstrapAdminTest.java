package com.dmg.notify.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @Test
    void warnsWhenSeededWithTheDefaultPassword(CapturedOutput out) {
        when(users.existsByRole(Role.PLATFORM_ADMIN)).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hash");

        new BootstrapAdmin(users, encoder, "admin", BootstrapAdmin.DEFAULT_PASSWORD).run(null);

        verify(users).save(any());
        assertThat(out.getAll()).contains("DEFAULT development password");
    }

    @Test
    void staysQuietWithACustomPasswordAndDoesNotReseed(CapturedOutput out) {
        when(users.existsByRole(Role.PLATFORM_ADMIN)).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hash");
        new BootstrapAdmin(users, encoder, "admin", "a-much-better-secret").run(null);
        assertThat(out.getAll()).doesNotContain("DEFAULT development password");

        when(users.existsByRole(Role.PLATFORM_ADMIN)).thenReturn(true);
        org.mockito.Mockito.clearInvocations(users);
        new BootstrapAdmin(users, encoder, "admin", BootstrapAdmin.DEFAULT_PASSWORD).run(null);
        verify(users, never()).save(any());
    }
}
