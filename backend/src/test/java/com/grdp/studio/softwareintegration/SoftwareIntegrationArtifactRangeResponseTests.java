package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.controller.SoftwareIntegrationRunController;
import com.grdp.studio.softwareintegration.service.SoftwareIntegrationRunService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoftwareIntegrationArtifactRangeResponseTests {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"0,4", "3,4", "7,1"})
    void converterProducesOneRangeHeaderAndExactlyRequestedBytes(long offset, int length) throws Exception {
        byte[] bytes = {0, 1, 2, 3, 4, 5, 6, 7};
        Path file = directory.resolve("CASE.EGRID");
        Files.write(file, bytes);
        var service = mock(SoftwareIntegrationRunService.class);
        when(service.downloadArtifactRange(215, 841, offset, length)).thenReturn(
                new SoftwareIntegrationRunService.ArtifactRangeDownload(file, "CASE.EGRID",
                        "application/octet-stream", offset, length, bytes.length));
        var response = new SoftwareIntegrationRunController(service).downloadArtifactRange(215, 841, offset, length);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        var output = new MockHttpOutputMessage();
        output.getHeaders().putAll(response.getHeaders());
        new ResourceRegionHttpMessageConverter().write(response.getBody(), MediaType.APPLICATION_OCTET_STREAM, output);
        assertThat(output.getHeaders().get(HttpHeaders.CONTENT_RANGE))
                .containsExactly("bytes %d-%d/8".formatted(offset, offset + length - 1));
        assertThat(output.getHeaders().getContentLength()).isEqualTo(length);
        assertThat(output.getBodyAsBytes()).isEqualTo(Arrays.copyOfRange(bytes, (int) offset, (int) offset + length));
    }
}
