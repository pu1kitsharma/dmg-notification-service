package com.dmg.notify.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.dmg.notify.dispatch.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class ApiIntegrationTest extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private final RequestPostProcessor platform = httpBasic("admin", "admin12345");

    private RequestPostProcessor createTenant(String name) throws Exception {
        String body = """
                {"name":"%s","ratePerSecond":100,"burst":100,"maxAttempts":3,
                 "adminUsername":"%s-admin","adminPassword":"secret-pass-1"}""".formatted(name, name);
        mvc.perform(post("/api/v1/tenants").with(platform).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return httpBasic(name + "-admin", "secret-pass-1");
    }

    private void createTemplate(RequestPostProcessor who) throws Exception {
        mvc.perform(post("/api/v1/templates").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"welcome\",\"channel\":\"EMAIL\",\"subject\":\"Hi {{name}}\",\"body\":\"Hello {{name}}\"}"))
                .andExpect(status().isCreated());
    }

    private static final String SUBMIT = "{\"channel\":\"EMAIL\",\"templateName\":\"welcome\",\"recipient\":\"a@b.com\",\"variables\":{\"name\":\"Ada\"}}";

    @Test
    void unauthenticatedIs401AndWrongRoleIs403() throws Exception {
        RequestPostProcessor acme = createTenant("acme");
        mvc.perform(get("/api/v1/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tenants").with(acme)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications").with(platform)).andExpect(status().isForbidden());
    }

    @Test
    void tenantsCannotSeeEachOthersData() throws Exception {
        RequestPostProcessor a = createTenant("tenant-a");
        RequestPostProcessor b = createTenant("tenant-b");
        createTemplate(a);
        createTemplate(b);

        String res = mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(res).get("id").asText();

        mvc.perform(get("/api/v1/notifications/" + id).with(a)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/" + id + "/cancel").with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications").with(b)).andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void idempotencyKeyReturnsSameNotification() throws Exception {
        RequestPostProcessor a = createTenant("idem");
        createTemplate(a);

        JsonNode first = json.readTree(mvc.perform(post("/api/v1/notifications").with(a).header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        JsonNode second = json.readTree(mvc.perform(post("/api/v1/notifications").with(a).header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        mvc.perform(get("/api/v1/notifications").with(a)).andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void validationErrorsAreClear() throws Exception {
        RequestPostProcessor a = createTenant("valid");
        createTemplate(a);

        // missing template variable -> 422
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"EMAIL\",\"templateName\":\"welcome\",\"recipient\":\"a@b.com\",\"variables\":{}}"))
                .andExpect(status().isUnprocessableEntity());
        // bad email -> 400
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON)
                .content(SUBMIT.replace("a@b.com", "not-an-email")))
                .andExpect(status().isBadRequest());
        // missing required fields -> 400
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // unknown template -> 404
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON)
                .content(SUBMIT.replace("welcome", "nope")))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledChannelAndDeactivatedTenantAreRejected() throws Exception {
        RequestPostProcessor a = createTenant("gated");
        createTemplate(a);
        mvc.perform(put("/api/v1/channels/EMAIL").with(a).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/channels/EMAIL").with(a).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        String tenantId = json.readTree(mvc.perform(get("/api/v1/tenants").with(platform)).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asText();
        mvc.perform(patch("/api/v1/tenants/" + tenantId).with(platform).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isForbidden());
    }

    @Test
    void endToEndSubmitDispatchAndReport() throws Exception {
        RequestPostProcessor a = createTenant("e2e");
        createTemplate(a);
        String id = json.readTree(mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        drain(3);

        mvc.perform(get("/api/v1/notifications/" + id).with(a))
                .andExpect(jsonPath("$.notification.status").value("SENT"))
                .andExpect(jsonPath("$.notification.body").value("Hello Ada"))
                .andExpect(jsonPath("$.events.length()").value(3));
        mvc.perform(get("/api/v1/reports/delivery").with(a))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byStatus.SENT").value(1));
    }

    @Test
    void reportBreaksDownByTemplateAndFailureReason() throws Exception {
        RequestPostProcessor a = createTenant("rep");
        createTemplate(a);
        for (String r : new String[] {"a@b.com", "c@d.com", "fail-permanent@x.com"}) {
            mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON)
                    .content(SUBMIT.replace("a@b.com", r))).andExpect(status().isAccepted());
        }
        drain(4);

        mvc.perform(get("/api/v1/reports/delivery").with(a))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.successRate").value(2.0 / 3))
                .andExpect(jsonPath("$.byTemplate[0].template").value("welcome"))
                .andExpect(jsonPath("$.byTemplate[0].byStatus.SENT").value(2))
                .andExpect(jsonPath("$.byTemplate[0].byStatus.DEAD").value(1))
                .andExpect(jsonPath("$.topFailures[0].count").value(1));
        mvc.perform(get("/api/v1/reports/delivery?channel=SMS").with(a))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.byTemplate.length()").value(0))
                .andExpect(jsonPath("$.successRate").doesNotExist());
    }

    @Test
    void listFiltersByStatusChannelAndDate() throws Exception {
        RequestPostProcessor a = createTenant("flt");
        createTemplate(a);
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON)
                .content(SUBMIT.replace("a@b.com", "fail-permanent@x.com"))).andExpect(status().isAccepted());
        drain(4);

        mvc.perform(get("/api/v1/notifications?status=DEAD").with(a)).andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/v1/notifications?channel=EMAIL").with(a)).andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/v1/notifications?channel=SMS").with(a)).andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/v1/notifications?to=2000-01-01T00:00:00Z").with(a)).andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/v1/notifications?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z").with(a))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchSubmitReportsPerItemOutcomes() throws Exception {
        RequestPostProcessor a = createTenant("batch");
        createTemplate(a);
        String body = "{\"items\":["
                + "{\"idempotencyKey\":\"b-1\",\"notification\":" + SUBMIT + "},"
                + "{\"idempotencyKey\":\"b-1\",\"notification\":" + SUBMIT + "},"
                + "{\"notification\":" + SUBMIT + "},"
                + "{\"notification\":" + SUBMIT.replace("welcome", "nope") + "},"
                + "{\"notification\":" + SUBMIT.replace("a@b.com", "not-an-email") + "}]}";

        mvc.perform(post("/api/v1/notifications/batch").with(a).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.results[0].outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[1].outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[1].id").exists())
                .andExpect(jsonPath("$.results[3].status").value(404))
                .andExpect(jsonPath("$.results[4].status").value(400));
        mvc.perform(get("/api/v1/notifications").with(a)).andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void batchLimitsAndShapeAreValidated() throws Exception {
        RequestPostProcessor a = createTenant("batchv");
        mvc.perform(post("/api/v1/notifications/batch").with(a).contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        String item = "{\"notification\":" + SUBMIT + "}";
        String tooMany = "{\"items\":[" + String.join(",", java.util.Collections.nCopies(101, item)) + "]}";
        mvc.perform(post("/api/v1/notifications/batch").with(a).contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/notifications/batch").with(a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"notification\":{}}]}")).andExpect(status().isBadRequest());
    }

    @Test
    void replayIsOnlyForDeadAndTenantScoped() throws Exception {
        RequestPostProcessor a = createTenant("rp-a");
        RequestPostProcessor b = createTenant("rp-b");
        createTemplate(a);
        String id = json.readTree(mvc.perform(post("/api/v1/notifications").with(a).contentType(MediaType.APPLICATION_JSON).content(SUBMIT))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/v1/notifications/" + id + "/replay").with(a)).andExpect(status().isConflict()); // still PENDING
        mvc.perform(post("/api/v1/notifications/" + id + "/replay").with(b)).andExpect(status().isNotFound());
    }

    @Test
    void templatesAreVersioned() throws Exception {
        RequestPostProcessor a = createTenant("ver");
        createTemplate(a);
        mvc.perform(post("/api/v1/templates").with(a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"welcome\",\"channel\":\"EMAIL\",\"subject\":\"v2\",\"body\":\"Hey {{name}}\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
    }
}
