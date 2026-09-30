package com.dmg.notify.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.dmg.notify.dispatch.AbstractIntegrationTest;
import com.dmg.notify.tenant.Tenant;
import com.dmg.notify.tenant.TenantDtos.CreateTenantRequest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ChannelConfigConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ChannelConfigRepository configs;

    @Test
    void concurrentFirstPutsOfTheSameChannelAllSucceedAndLeaveOneRow() throws Exception {
        int threads = 16;
        // a fresh tenant per round: only the very first PUT of a (tenant, channel) inserts, so that is the race
        for (int round = 0; round < 5; round++) {
            String user = "chan-race-" + round + "-" + System.nanoTime();
            Tenant t = tenantService.create(new CreateTenantRequest(user, 100, 100, 3, user, "secret-pass-1"));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = IntStream.range(0, threads).mapToObj(i -> pool.submit((Callable<Integer>) () -> {
                start.await();
                return mvc.perform(put("/api/v1/channels/SMS").with(httpBasic(user, "secret-pass-1"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"senderId\":\"sender-" + i + "\"}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // PUT is an idempotent upsert: every caller gets 200 (no 409 from a lost insert race), one row survives
            List<Integer> statuses = results.stream().map(f -> {
                try { return f.get(); } catch (Exception e) { throw new AssertionError("PUT failed: " + e, e); }
            }).toList();
            assertThat(statuses).as("round %d", round).containsOnly(200);
            List<ChannelConfig> rows = configs.findByTenantId(t.getId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).isEnabled()).isFalse();
            assertThat(rows.get(0).getSenderId()).startsWith("sender-");
        }
    }
}
