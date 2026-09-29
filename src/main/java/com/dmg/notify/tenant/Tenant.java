package com.dmg.notify.tenant;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tenants")
public class Tenant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private boolean active = true;
    private int ratePerSecond;
    private int burst;
    private int maxAttempts;
    private Instant createdAt;

    protected Tenant() {}

    public Tenant(String name, int ratePerSecond, int burst, int maxAttempts, Instant createdAt) {
        this.name = name;
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.maxAttempts = maxAttempts;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public int getRatePerSecond() { return ratePerSecond; }
    public int getBurst() { return burst; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public void setActive(boolean active) { this.active = active; }
    public void setRatePerSecond(int v) { this.ratePerSecond = v; }
    public void setBurst(int v) { this.burst = v; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
}
