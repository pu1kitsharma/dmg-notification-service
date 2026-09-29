package com.dmg.notify.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureReasonNormalizationTest {
    @Test
    void masksIdsAndNumbersSoSimilarFailuresShareABucket() {
        assertThat(DeliveryReportController.normalizeReason("Provider 503 for msg 8f0c2a9e-1b2c-4d3e-8f90-0123456789ab (req 99123)"))
                .isEqualTo("provider 503 for msg <id> (req <n>)");
        assertThat(DeliveryReportController.normalizeReason("  Provider   unavailable "))
                .isEqualTo(DeliveryReportController.normalizeReason("provider unavailable"));
    }

    @Test
    void handlesNullBlankAndVeryLongReasons() {
        assertThat(DeliveryReportController.normalizeReason(null)).isEqualTo("unknown");
        assertThat(DeliveryReportController.normalizeReason("  ")).isEqualTo("unknown");
        assertThat(DeliveryReportController.normalizeReason("x".repeat(500))).hasSize(120);
    }
}
