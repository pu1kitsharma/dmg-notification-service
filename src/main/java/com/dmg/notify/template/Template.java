package com.dmg.notify.template;

import com.dmg.notify.channel.ChannelType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "templates")
public class Template {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long tenantId;
    private String name;
    @Enumerated(EnumType.STRING)
    private ChannelType channel;
    private int version;
    private String subject;
    private String body;
    private Instant createdAt;

    protected Template() {}

    public Template(Long tenantId, String name, ChannelType channel, int version, String subject, String body, Instant createdAt) {
        this.tenantId = tenantId;
        this.name = name;
        this.channel = channel;
        this.version = version;
        this.subject = subject;
        this.body = body;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public String getName() { return name; }
    public ChannelType getChannel() { return channel; }
    public int getVersion() { return version; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
