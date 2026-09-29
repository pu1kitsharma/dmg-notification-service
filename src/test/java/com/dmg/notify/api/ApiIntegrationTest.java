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
        mvc.perform(get("/api/v1/notifications").with(b)).andExpect(jsonPath("$.totalElements").value(0));
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
        mvc.perform(get("/api/v1/notifications").with(a)).andExpect(jsonPath("$.totalElements").value(1));
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
