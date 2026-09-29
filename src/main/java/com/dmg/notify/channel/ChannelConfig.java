package com.dmg.notify.channel;

import jakarta.persistence.*;

@Entity
@Table(name = "channel_configs")
public class ChannelConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    @Enumerated(EnumType.STRING)
    private ChannelType channel;
    private boolean enabled;
    private String senderId;

    protected ChannelConfig() {}

    public ChannelConfig(Long tenantId, ChannelType channel, boolean enabled, String senderId) {
        this.tenantId = tenantId;
        this.channel = channel;
        this.enabled = enabled;
        this.senderId = senderId;
    }

    public ChannelType getChannel() { return channel; }
    public boolean isEnabled() { return enabled; }
    public String getSenderId() { return senderId; }
    public void update(boolean enabled, String senderId) { this.enabled = enabled; this.senderId = senderId; }
}
