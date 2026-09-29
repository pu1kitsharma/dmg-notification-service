package com.dmg.notify.dispatch;

import com.dmg.notify.channel.ChannelType;
import jakarta.annotation.PreDestroy;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** One bounded pool per channel: a slow SMS provider cannot starve email, and memory stays bounded. */
@Component
public class ChannelExecutors {
    private final Map<ChannelType, ThreadPoolExecutor> pools = new EnumMap<>(ChannelType.class);
    private final AtomicInteger inFlight = new AtomicInteger();

    public ChannelExecutors(DispatchProperties props) {
        for (ChannelType type : ChannelType.values()) {
            AtomicInteger n = new AtomicInteger();
            pools.put(type, new ThreadPoolExecutor(props.poolSize(), props.poolSize(), 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(props.queueCapacity()),
                    r -> {
                        Thread t = new Thread(r, "notify-" + type.name().toLowerCase() + "-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy()));
        }
    }

    /** @return false when the pool and its queue are full (backpressure). */
    public boolean trySubmit(ChannelType type, Runnable task) {
        inFlight.incrementAndGet();
        try {
            pools.get(type).execute(() -> {
                try {
                    task.run();
                } finally {
                    inFlight.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            return false;
        }
    }

    /** Test helper: block until all pools are idle. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (inFlight.get() == 0) return true;
            Thread.sleep(5);
        }
        return false;
    }

    @PreDestroy
    void shutdown() { pools.values().forEach(ThreadPoolExecutor::shutdownNow); }
}
