package com.grdp.studio.softwareintegration.preprocessing;

import com.grdp.studio.common.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned engineering templates. Client values never supply source text or replacement offsets. */
@Component
public final class EclipsePreprocessingCatalog {
    private final Map<String, JsonNode> templates;

    public EclipsePreprocessingCatalog(ObjectMapper mapper) {
        var loaded = new LinkedHashMap<String, JsonNode>();
        for (var id : List.of("e100", "compositional", "thermal")) {
            try (var input = new ClassPathResource("softwareintegration/preprocessing/" + id + ".json").getInputStream()) {
                var template = mapper.readTree(input);
                if (!sha256(template.path("source").asText().getBytes(StandardCharsets.US_ASCII))
                        .equals(template.path("normalizedSourceSha256").asText())) {
                    throw new IllegalStateException("Preprocessing template source checksum mismatch: " + id);
                }
                loaded.put(id, template);
            } catch (IOException exception) {
                throw new IllegalStateException("Preprocessing template unavailable: " + id, exception);
            }
        }
        templates = Map.copyOf(loaded);
    }

    public JsonNode require(String id) {
        var template = id == null ? null : templates.get(id);
        if (template == null) throw new BusinessException(400, "请选择 E100、E300组分或E300热采模板");
        return template;
    }

    public List<JsonNode> summaries() {
        return List.of("e100", "compositional", "thermal").stream().map(id -> {
            var value = require(id).deepCopy();
            for (var field : List.of("source", "fields", "gridOperations", "fixedOptions", "wells", "completions", "controls")) {
                ((tools.jackson.databind.node.ObjectNode) value).remove(field);
            }
            return value;
        }).toList();
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
