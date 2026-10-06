package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in read-only smoke against an explicitly supplied real Worker creation; never runs the SDK. */
@EnabledIfSystemProperty(named = "grdp.template.smoke", matches = "true")
class WorkerTemplateCreationNativeSmokeTests {
    @Test void recoverAndDownloadOfficiallyCreatedModel() {
        var properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl(System.getProperty("grdp.template.worker-url"));
        var client = new WorkerTemplateCreationClient(properties, new ObjectMapper());
        UUID id = UUID.fromString(System.getProperty("grdp.template.creation-id"));
        var record = client.find(id);
        assertThat(record).isNotNull(); assertThat(record.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(record.path("result").path("calculationVerified").asBoolean()).isTrue();
        assertThat(record.path("result").path("nativeState").asText()).isEqualTo("Completed");
        assertThat(record.path("result").path("profile").size()).isGreaterThan(1);
        byte[] model = client.download(id, record);
        assertThat(model.length).isEqualTo(record.path("model").path("sizeBytes").asInt());
        assertThat(client.find(id)).isEqualTo(record);
    }
}
