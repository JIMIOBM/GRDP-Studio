package com.grdp.studio.softwareintegration.preprocessing;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EclipsePreprocessingGrid {
    record Cell(int i, int j, int k, double x, double y, double top, double dx, double dy, double dz,
                double porosity, double permx, double permy, double permz, boolean active) { }
    record Completion(int i, int j, int k1, int k2, String status) { }
    record Well(String name, int i, int j, List<Integer> layers, List<Completion> completions) { }
    record Preview(List<Cell> cells, List<Well> wells, int activeCells, Map<String, double[]> properties) { }

    static Preview evaluate(JsonNode template, Map<String, String> values) {
        var dimensions = template.path("dimensions");
        int nx = dimensions.path("nx").asInt(), ny = dimensions.path("ny").asInt(), nz = dimensions.path("nz").asInt();
        int count = nx * ny * nz;
        var arrays = new LinkedHashMap<String, double[]>();
        var fieldMap = new HashMap<String, JsonNode>();
        template.path("fields").forEach(field -> fieldMap.put(field.path("id").asText(), field));
        var active = new double[count];
        Arrays.fill(active, 1); // ECLIPSE's documented ACTNUM default; not a missing physical property.
        arrays.put("ACTNUM", active);
        for (var operation : template.path("gridOperations")) {
            String target = operation.path("target").asText();
            if ("ARRAY".equals(operation.path("kind").asText())) {
                var expanded = new ArrayList<Double>();
                for (var id : operation.path("fieldIds")) {
                    var field = fieldMap.get(id.asText());
                    double value = Double.parseDouble(values.get(id.asText()));
                    for (int repeat = 0; repeat < field.path("repeat").asInt(); repeat++) expanded.add(value);
                }
                var array = blank(count);
                for (int index = 0; index < count; index++) {
                    int source = switch (target) {
                        case "DXV" -> index % nx;
                        case "DYV" -> index / nx % ny;
                        case "DZV" -> index / (nx * ny);
                        default -> index;
                    };
                    if (source < expanded.size()) array[index] = expanded.get(source);
                }
                arrays.put(switch (target) { case "DXV" -> "DX"; case "DYV" -> "DY"; case "DZV" -> "DZ"; default -> target; }, array);
                continue;
            }
            double[] array = arrays.computeIfAbsent(target, ignored -> blank(count));
            var ranges = operation.path("ranges");
            int[] bounds = {1, nx, 1, ny, 1, nz};
            for (int index = 0; index < Math.min(6, ranges.size()); index++) {
                if (!ranges.get(index).isNull()) bounds[index] = Integer.parseInt(ranges.get(index).asText());
            }
            double value = operation.hasNonNull("valueId") ? Double.parseDouble(values.get(operation.path("valueId").asText()))
                    : operation.has("value") ? Double.parseDouble(operation.path("value").asText()) : 0;
            for (int k = bounds[4]; k <= bounds[5]; k++) for (int j = bounds[2]; j <= bounds[3]; j++) for (int i = bounds[0]; i <= bounds[1]; i++) {
                int index = (k - 1) * nx * ny + (j - 1) * nx + i - 1;
                switch (operation.path("kind").asText()) {
                    case "EQUALS" -> array[index] = value;
                    case "MULTIPLY" -> array[index] *= value;
                    case "COPY" -> array[index] = arrays.get(operation.path("source").asText())[index];
                    default -> throw new IllegalStateException("Unsupported template grid operation");
                }
            }
        }
        var cells = new ArrayList<Cell>();
        var tops = arrays.get("TOPS");
        for (int k = 0; k < nz; k++) for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            int index = k * nx * ny + j * nx + i;
            if (k > 0 && !Double.isFinite(tops[index])) tops[index] = tops[index - nx * ny] + arrays.get("DZ")[index - nx * ny];
            double x = 0, y = 0;
            for (int column = 0; column < i; column++) x += arrays.get("DX")[k * nx * ny + j * nx + column];
            for (int row = 0; row < j; row++) y += arrays.get("DY")[k * nx * ny + row * nx + i];
            cells.add(new Cell(i + 1, j + 1, k + 1, x, y, tops[index], arrays.get("DX")[index], arrays.get("DY")[index],
                    arrays.get("DZ")[index], arrays.get("PORO")[index], arrays.get("PERMX")[index], arrays.get("PERMY")[index],
                    arrays.get("PERMZ")[index], arrays.get("ACTNUM")[index] == 1));
        }
        var wells = new ArrayList<Well>();
        for (var definition : template.path("wells")) {
            var record = recordValues(definition, fieldMap, values);
            var layers = new ArrayList<Integer>();
            var completions = new ArrayList<Completion>();
            int headI = Integer.parseInt(record.get(2)), headJ = Integer.parseInt(record.get(3));
            for (var completion : template.path("completions")) {
                var entry = recordValues(completion, fieldMap, values);
                if (!record.get(0).equals(entry.get(0))) continue;
                completions.add(new Completion(connectionCoordinate(entry.get(1), headI), connectionCoordinate(entry.get(2), headJ),
                        Integer.parseInt(entry.get(3)), Integer.parseInt(entry.get(4)), entry.get(5) == null ? "OPEN" : entry.get(5)));
                for (int k = Integer.parseInt(entry.get(3)); k <= Integer.parseInt(entry.get(4)); k++) if (!layers.contains(k)) layers.add(k);
            }
            wells.add(new Well(record.get(0), headI, headJ, layers, completions));
        }
        return new Preview(cells, wells, (int) cells.stream().filter(Cell::active).count(), arrays);
    }

    static int connectionCoordinate(String value, int wellHead) {
        return value == null || Integer.parseInt(value) == 0 ? wellHead : Integer.parseInt(value);
    }

    static List<String> recordValues(JsonNode definition, Map<String, JsonNode> fields, Map<String, String> values) {
        var result = new ArrayList<String>();
        definition.path("values").forEach(value -> result.add(value.isNull() ? null : value.asText()));
        for (var id : definition.path("fields")) {
            var field = fields.get(id.asText());
            result.set(field.path("column").asInt(), values.get(id.asText()));
        }
        return result;
    }

    private static double[] blank(int size) {
        var result = new double[size];
        Arrays.fill(result, Double.NaN);
        return result;
    }
}
