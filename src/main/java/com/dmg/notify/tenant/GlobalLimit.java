package com.dmg.notify.tenant;

import jakarta.persistence.*;

@Entity
@Table(name = "global_limits")
public class GlobalLimit {
    public static final int ID = 1;

    @Id
    private Integer id = ID;
    private int ratePerSecond;
    private int burst;

    protected GlobalLimit() {}

    public int getRatePerSecond() { return ratePerSecond; }
    public int getBurst() { return burst; }
    public void set(int ratePerSecond, int burst) { this.ratePerSecond = ratePerSecond; this.burst = burst; }
}
