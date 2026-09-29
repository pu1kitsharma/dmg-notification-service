package com.dmg.notify.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.dispatch.AbstractIntegrationTest;
import com.dmg.notify.template.TemplateService.CreateTemplateRequest;
import com.dmg.notify.tenant.Tenant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TemplateConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void concurrentCreatesOfTheSameTemplateAllSucceedWithDistinctConsecutiveVersions() throws Exception {
        Tenant t = newTenant(1000, 1000, 3);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = IntStream.range(0, threads).mapToObj(i -> pool.submit((Callable<Integer>) () -> {
            start.await();
            return templateService.create(t.getId(), new CreateTemplateRequest("promo", ChannelType.EMAIL, "s" + i, "body " + i)).getVersion();
        })).toList();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // no 500s: every caller got a version, all distinct, and together they are exactly 1..12
        List<Integer> versions = results.stream().map(f -> {
            try { return f.get(); } catch (Exception e) { throw new AssertionError("create failed: " + e, e); }
        }).sorted().toList();
        assertThat(versions).containsExactlyElementsOf(IntStream.rangeClosed(1, threads).boxed().toList());
        assertThat(templateService.latest(t.getId(), "promo", ChannelType.EMAIL).getVersion()).isEqualTo(threads);
    }
}
