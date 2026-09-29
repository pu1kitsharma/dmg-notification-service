package com.dmg.notify.dispatch;

import com.dmg.notify.channel.Channel;
import com.dmg.notify.channel.ChannelType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double: counts every provider call per deliveryKey and can be scripted to fail. */
class ScriptedChannel implements Channel {
    private final ChannelType type;
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> successes = new ConcurrentHashMap<>();
    final Map<String, String> senderIds = new ConcurrentHashMap<>();
    volatile int transientFailuresPerKey = 0;

    ScriptedChannel(ChannelType type) { this.type = type; }

    @Override public ChannelType type() { return type; }

    @Override
    public void send(DeliveryRequest r) {
        senderIds.put(r.deliveryKey(), String.valueOf(r.senderId()));
        int call = calls.computeIfAbsent(r.deliveryKey(), k -> new AtomicInteger()).incrementAndGet();
        if (r.recipient().contains("fail-permanent")) throw new PermanentChannelException("bad recipient");
        if (r.recipient().contains("fail-always") || call <= transientFailuresPerKey) {
            throw new TransientChannelException("provider down");
        }
        successes.computeIfAbsent(r.deliveryKey(), k -> new AtomicInteger()).incrementAndGet();
    }

    int totalSuccesses() { return successes.values().stream().mapToInt(AtomicInteger::get).sum(); }
}
