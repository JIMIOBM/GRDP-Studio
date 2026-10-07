package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.client.WorkerTemplateCreationClient;
import com.grdp.studio.softwareintegration.support.SoftwareIntegrationProperties;
import com.grdp.studio.softwareintegration.support.TemplateCreationSourceValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in transport smoke against a supplied successful creation; never creates or reruns a model. */
@EnabledIfSystemProperty(named = "grdp.template.smoke", matches = "true")
class WorkerTemplateCreationNativeSmokeTests {
    @Test void recoverAndDownloadOfficiallyCreatedModel() throws Exception {
        var properties = new SoftwareIntegrationProperties();
        properties.setWorkerBaseUrl(System.getProperty("grdp.template.worker-url"));
        var client = new WorkerTemplateCreationClient(properties, new ObjectMapper());
        assertThat(client.executionBudgetSeconds()).isBetween(1, 87_000);
        UUID id = UUID.fromString(System.getProperty("grdp.template.creation-id"));
        var record = client.find(id);
        assertThat(record).isNotNull(); assertThat(record.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(record.path("result").path("calculationVerified").asBoolean()).isTrue();
        assertThat(record.path("result").path("nativeState").asText()).isEqualTo("Completed");
        assertThat(record.path("result").path("profile").size()).isGreaterThan(1);
        String requestFile = System.getProperty("grdp.template.request-file");
        if (requestFile != null) {
            var request = new ObjectMapper().readTree(Files.readString(Path.of(requestFile)));
            assertThat(request.path("requestId").asText()).isEqualTo(id.toString());
            TemplateCreationSourceValidator.requireSuccess(request, record);
        }
        byte[] model = client.download(id, record);
        assertThat(model.length).isEqualTo(record.path("model").path("sizeBytes").asInt());
        assertThat(client.find(id)).isEqualTo(record);
        assertThat(client.cancel(id).record()).isEqualTo(record);
    }
}
