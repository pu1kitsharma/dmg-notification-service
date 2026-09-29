package com.dmg.notify.channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChannelRegistry {
    private final Map<ChannelType, Channel> channels = new ConcurrentHashMap<>();

    public ChannelRegistry(@Value("${app.channels.latency-ms:0}") long latencyMs) {
        for (ChannelType t : ChannelType.values()) channels.put(t, new SimulatedChannel(t, latencyMs));
    }

    public Channel get(ChannelType type) { return channels.get(type); }

    /** Swap in a real provider (or a test double). */
    public void register(Channel channel) { channels.put(channel.type(), channel); }
}
