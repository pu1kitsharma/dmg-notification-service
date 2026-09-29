package com.dmg.notify.tenant;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class TenantDtos {
    private TenantDtos() {}

    public record CreateTenantRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(1) @Max(100_000) Integer ratePerSecond,
            @Min(1) @Max(1_000_000) Integer burst,
            @Min(1) @Max(20) Integer maxAttempts,
            @NotBlank @Size(max = 100) String adminUsername,
            @NotBlank @Size(min = 8, max = 72) String adminPassword) {}

    public record UpdateTenantRequest(
            Boolean active,
            @Min(1) @Max(100_000) Integer ratePerSecond,
            @Min(1) @Max(1_000_000) Integer burst,
            @Min(1) @Max(20) Integer maxAttempts) {}

    public record TenantResponse(Long id, String name, boolean active, int ratePerSecond, int burst,
                                 int maxAttempts, Instant createdAt) {
        static TenantResponse from(Tenant t) {
            return new TenantResponse(t.getId(), t.getName(), t.isActive(), t.getRatePerSecond(),
                    t.getBurst(), t.getMaxAttempts(), t.getCreatedAt());
        }
    }
}
