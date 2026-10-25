package com.grdp.studio.softwareintegration.preprocessing;

import java.util.List;
import java.util.UUID;

public record EclipsePreprocessingInput(String templateId, String templateVersion, String modelName,
        UUID sourceId, UUID requestId, boolean confirmedDefaults, boolean calculate, List<Value> values) {
    public record Value(String id, String value, String unit, String sheet, int row) { }
    public record Issue(String sheet, int row, String field, String id, String message) { }
}
