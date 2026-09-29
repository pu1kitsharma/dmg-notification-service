package com.dmg.notify.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dmg.notify.common.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void substitutesVariablesIncludingWhitespaceAndRepeats() {
        String out = TemplateRenderer.render("Hi {{ name }}, {{name}}! Code: {{code}}", Map.of("name", "Ada", "code", "$1\\2"));
        assertThat(out).isEqualTo("Hi Ada, Ada! Code: $1\\2");
    }

    @Test
    void missingVariablesAreReportedTogether() {
        assertThatThrownBy(() -> TemplateRenderer.render("{{a}} {{b}} {{c}}", Map.of("b", "x")))
                .isInstanceOf(ApiException.Unprocessable.class)
                .hasMessageContaining("a, c");
    }

    @Test
    void nullTemplateStaysNull() {
        assertThat(TemplateRenderer.render(null, Map.of())).isNull();
    }
}
