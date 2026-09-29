package com.dmg.notify.dispatch;

import com.dmg.notify.channel.ChannelRegistry;
import com.dmg.notify.channel.ChannelType;
import com.dmg.notify.notification.Notification;
import com.dmg.notify.notification.NotificationRepository;
import com.dmg.notify.notification.NotificationDtos.SubmitRequest;
import com.dmg.notify.notification.NotificationService;
import com.dmg.notify.notification.NotificationStatus;
import com.dmg.notify.template.TemplateService;
import com.dmg.notify.template.TemplateService.CreateTemplateRequest;
import com.dmg.notify.tenant.Tenant;
import com.dmg.notify.tenant.TenantDtos.CreateTenantRequest;
import com.dmg.notify.tenant.TenantService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "app.dispatch.enabled=false",
        "app.dispatch.batch-per-tenant=10",
        "app.retry.base-delay-ms=1",
        "app.retry.max-delay-ms=4"})
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
public abstract class AbstractIntegrationTest {
    @Autowired protected TenantService tenantService;
    @Autowired protected TemplateService templateService;
    @Autowired protected NotificationService notificationService;
    @Autowired protected NotificationRepository notificationRepo;
    @Autowired protected Dispatcher dispatcher;
    @Autowired protected ChannelExecutors executors;
    @Autowired protected ChannelRegistry registry;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;

    protected ScriptedChannel email;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void cleanSlate() {
        clock.reset();
        jdbc.update("delete from notification_events");
        jdbc.update("delete from notification_attempts");
        jdbc.update("delete from notifications");
        jdbc.update("delete from templates");
        jdbc.update("delete from channel_configs");
        jdbc.update("delete from app_users where tenant_id is not null");
        jdbc.update("delete from tenants");
        email = new ScriptedChannel(ChannelType.EMAIL);
        registry.register(email);
    }

    protected Tenant newTenant(int rate, int burst, int maxAttempts) {
        int n = SEQ.incrementAndGet();
        Tenant t = tenantService.create(new CreateTenantRequest("tenant-" + n, rate, burst, maxAttempts, "admin-" + n, "password-" + n));
        templateService.create(t.getId(), new CreateTemplateRequest("welcome", ChannelType.EMAIL, "Hi {{name}}", "Hello {{name}}"));
        return t;
    }

    protected Notification submit(Tenant t, String recipient) {
        return notificationService.submit(t.getId(),
                new SubmitRequest(ChannelType.EMAIL, "welcome", recipient, Map.of("name", "Ada"), null), null).notification();
    }

    protected Notification submitAt(Tenant t, String recipient, Instant when) {
        return notificationService.submit(t.getId(),
                new SubmitRequest(ChannelType.EMAIL, "welcome", recipient, Map.of("name", "Ada"), when), null).notification();
    }

    protected NotificationStatus statusOf(String id) { return notificationRepo.findById(id).orElseThrow().getStatus(); }

    /** Drive dispatch cycles until every notification of the tenant is terminal (or give up). */
    protected void drain(int maxCycles) throws InterruptedException {
        for (int i = 0; i < maxCycles; i++) {
            dispatcher.runOnce();
            executors.awaitIdle(2000);
            clock.advance(java.time.Duration.ofMillis(10)); // lets retry backoff (<= a few ms in tests) elapse without sleeping
        }
    }
}
