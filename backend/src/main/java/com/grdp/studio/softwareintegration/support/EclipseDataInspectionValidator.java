package com.grdp.studio.softwareintegration.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Allows only the small, display-safe ECLIPSE deck metadata contract. */
public final class EclipseDataInspectionValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SECTION_ORDER = List.of("RUNSPEC", "GRID", "EDIT", "PROPS", "REGIONS", "SOLUTION", "SUMMARY", "SCHEDULE");
    private static final List<String> PHASE_ORDER = List.of("OIL", "WATER", "GAS");
    private static final Set<String> ROOT_FIELDS_V1 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions");
    private static final Set<String> ROOT_FIELDS_V2 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions", "wellNames", "scheduleTimeline");
    private static final Set<String> ROOT_FIELDS_V3 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions", "wellNames", "scheduleTimeline", "packageFiles");
    private static final Set<String> ROOT_FIELDS_V4 = Set.of("schemaVersion", "caseName", "sections", "unitSystem", "phases", "dimensions", "wellNames", "scheduleTimeline", "packageFiles", "scheduleMetadata");
    private static final Set<String> PACKAGE_FILE_FIELDS = Set.of("relativePath", "sizeBytes", "sha256");
    private static final Set<String> SCHEDULE_METADATA_FIELDS = Set.of("wells", "groups", "records", "completions");
    private static final Set<String> SCHEDULE_WELL_FIELDS = Set.of("name", "group", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_GROUP_FIELDS = Set.of("name", "parent", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_RECORD_FIELDS = Set.of("keyword", "values", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_COMPLETION_FIELDS = Set.of("keyword", "well", "i", "j", "k1", "k2", "status", "sourceFile", "lineNumber");
    private static final Set<String> SCHEDULE_RECORD_KEYWORDS = Set.of("WELSPECS", "WELSPECL", "GRUPTREE", "COMPDAT", "COMPDATM", "WELOPEN", "WCONHIST", "WCONINJE", "WCONPROD");
    private static final Set<String> DATE_FIELDS = Set.of("day", "month", "year", "time");
    private static final Set<String> DATES_EVENT_FIELDS = Set.of("kind", "records");
    private static final Set<String> TSTEP_EVENT_FIELDS = Set.of("kind", "steps");
    private static final Set<String> DATES_EVENT_FIELDS_V4 = Set.of("kind", "records", "sourceFile", "lineNumber");
    private static final Set<String> TSTEP_EVENT_FIELDS_V4 = Set.of("kind", "steps", "sourceFile", "lineNumber");
    private static final Set<String> UNIT_SYSTEMS = Set.of("METRIC", "FIELD", "LAB", "PVT-M");
    private static final Set<String> MONTHS = Set.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final int MAX_CASE_NAME_LENGTH = 255;
    private static final int MAX_DIMENSION = 1_000_000;
    private static final int MAX_WELL_NAMES = 1_000;
    private static final int MAX_SCHEDULE_EVENTS = 1_000;
    private static final int MAX_DATE_RECORDS = 4_000;
    private static final int MAX_TSTEP_STEPS = 8_000;
    private static final int MAX_SCHEDULE_METADATA_WELLS = 1_000;
    private static final int MAX_SCHEDULE_METADATA_GROUPS = 1_000;
    private static final int MAX_SCHEDULE_METADATA_RECORDS = 4_000;
    private static final int MAX_SCHEDULE_METADATA_VALUES = 128;
    private static final int MAX_SCHEDULE_COMPLETIONS = 4_000;
    private static final int MAX_WELL_NAME_SCALARS = 128;
    private static final Pattern DAY = Pattern.compile("(?:[1-9]|[12][0-9]|3[01])");
    private static final Pattern YEAR = Pattern.compile("[0-9]{4}");
    private static final Pattern TIME = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?");
    private static final Pattern DECIMAL = Pattern.compile("\\+?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]+)?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private EclipseDataInspectionValidator() {}

    public static String validateAndSerialize(JsonNode value, ObjectMapper objectMapper) {
        JsonNode validated = validate(value, objectMapper);
        try { return objectMapper.writeValueAsString(validated); }
        catch (Exception exception) { throw new IllegalArgumentException("inspection serialization failed", exception); }
    }

    public static JsonNode parsePersisted(String value) {
        if (value == null) return null;
        try { return validate(MAPPER.readTree(value), MAPPER); }
        catch (Exception exception) { return null; }
    }

    private static JsonNode validate(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isObject()) fail();
        ObjectNode input = (ObjectNode) value;
        String schemaVersion = text(input, "schemaVersion");
        if ("eclipse-data-inspection/1".equals(schemaVersion)) return validateV1(input, mapper);
        if ("eclipse-data-inspection/2".equals(schemaVersion)) return validateV2(input, mapper);
        if ("eclipse-data-inspection/3".equals(schemaVersion)) return validateV3(input, mapper);
        if ("eclipse-data-inspection/23".equals(schemaVersion)) return validateV23(input, mapper);
        if ("eclipse-data-inspection/22".equals(schemaVersion)) return validateV22(input, mapper);
        if ("eclipse-data-inspection/21".equals(schemaVersion)) return validateV21(input, mapper);
        if ("eclipse-data-inspection/20".equals(schemaVersion)) return validateV20(input, mapper);
        if ("eclipse-data-inspection/19".equals(schemaVersion)) return validateV19(input, mapper);
        if ("eclipse-data-inspection/18".equals(schemaVersion)) return validateV18(input, mapper);
        if ("eclipse-data-inspection/17".equals(schemaVersion)) return validateV17(input, mapper);
        if ("eclipse-data-inspection/16".equals(schemaVersion)) return validateV16(input, mapper);
        if ("eclipse-data-inspection/14".equals(schemaVersion)) return validateV14(input, mapper);
        if ("eclipse-data-inspection/15".equals(schemaVersion)) return validateV15(input, mapper);
        if ("eclipse-data-inspection/13".equals(schemaVersion)) return validateV13(input, mapper);
        if ("eclipse-data-inspection/12".equals(schemaVersion)) return validateV12(input, mapper);
        if ("eclipse-data-inspection/11".equals(schemaVersion)) return validateV11(input, mapper);
        if ("eclipse-data-inspection/10".equals(schemaVersion)) return validateV10(input, mapper);
        if ("eclipse-data-inspection/9".equals(schemaVersion)) return validateV9(input, mapper);
        if ("eclipse-data-inspection/8".equals(schemaVersion)) return validateV8(input, mapper);
        if ("eclipse-data-inspection/7".equals(schemaVersion)) return validateV7(input, mapper);
        if ("eclipse-data-inspection/6".equals(schemaVersion)) return validateV6(input, mapper);
        if ("eclipse-data-inspection/5".equals(schemaVersion)) return validateV5(input, mapper);
        if ("eclipse-data-inspection/4".equals(schemaVersion)) return validateV4(input, mapper);
        fail();
        return null;
    }

    private static JsonNode validateV1(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V1.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V1.contains(field.getKey())) fail(); });
        String caseName = text(input, "caseName");
        if (!validCaseName(caseName)) fail();
        ArrayNode sections = orderedStrings(input.get("sections"), SECTION_ORDER, true);
        JsonNode unit = input.get("unitSystem");
        if (!(unit == null || unit.isNull() || unit.isTextual() && UNIT_SYSTEMS.contains(unit.asText()))) fail();
        ArrayNode phases = orderedStrings(input.get("phases"), PHASE_ORDER, true);
        JsonNode dimensions = input.get("dimensions");
        if (!(dimensions == null || dimensions.isNull() || validDimensions(dimensions))) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "eclipse-data-inspection/1");
        result.put("caseName", caseName);
        result.set("sections", sections);
        if (unit == null || unit.isNull()) result.putNull("unitSystem"); else result.put("unitSystem", unit.asText());
        result.set("phases", phases);
        if (dimensions == null || dimensions.isNull()) result.putNull("dimensions"); else result.set("dimensions", dimensions);
        return result;
    }

    private static JsonNode validateV2(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V2.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V2.contains(field.getKey())) fail(); });
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/2");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper));
        return result;
    }

    private static JsonNode validateV14(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V3); fields.add("simulation");
        if (input.has("compositionalProductionContext")) fields.add("compositionalProductionContext");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (input.has("compositionalProductionContext") && !EclipseCompositionalProductionParameters.validContext(input.get("compositionalProductionContext"), input)) fail();
        if (!EclipseSimulationMetadataValidator.valid(input.get("simulation")) || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText()) || input.path("dimensions").isNull()) fail();
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/14");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper, true));
        result.set("packageFiles", packageFiles(input.get("packageFiles"), mapper));
        result.set("simulation", input.get("simulation").deepCopy());
        if (input.has("compositionalProductionContext")) result.set("compositionalProductionContext", input.get("compositionalProductionContext").deepCopy());
        return result;
    }

    private static JsonNode validateV22(ObjectNode input, ObjectMapper mapper) {
        if (!EclipseCompositionalFluidPropertyParameters.validEosPropertyContexts(input.get("compositionalEosPropertyContexts"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalEosPropertyContexts"); compatible.put("schemaVersion", "eclipse-data-inspection/21");
        var result = (ObjectNode) validateV21(compatible, mapper); result.put("schemaVersion", "eclipse-data-inspection/22");
        result.set("compositionalEosPropertyContexts", input.get("compositionalEosPropertyContexts").deepCopy()); return result;
    }

    private static JsonNode validateV21(ObjectNode input, ObjectMapper mapper) {
        if (!EclipseCompositionalFluidPropertyParameters.validAcentricFactorContext(input.get("compositionalAcentricFactorContext"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalAcentricFactorContext"); compatible.put("schemaVersion", "eclipse-data-inspection/20");
        var result = (ObjectNode) validateV20(compatible, mapper); result.put("schemaVersion", "eclipse-data-inspection/21");
        result.set("compositionalAcentricFactorContext", input.get("compositionalAcentricFactorContext").deepCopy()); return result;
    }

    private static JsonNode validateV20(ObjectNode input, ObjectMapper mapper) {
        if (!EclipseCompositionalFluidPropertyParameters.validCriticalZContext(input.get("compositionalCriticalZContext"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalCriticalZContext"); compatible.put("schemaVersion", "eclipse-data-inspection/19");
        var result = (ObjectNode) validateV19(compatible, mapper); result.put("schemaVersion", "eclipse-data-inspection/20");
        result.set("compositionalCriticalZContext", input.get("compositionalCriticalZContext").deepCopy()); return result;
    }

    private static JsonNode validateV19(ObjectNode input, ObjectMapper mapper) {
        if (!EclipseCompositionalFluidPropertyParameters.validTemperatureContext(input.get("compositionalTemperatureContext"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalTemperatureContext"); compatible.put("schemaVersion", "eclipse-data-inspection/17");
        var result = (ObjectNode) validateV17(compatible, mapper); result.put("schemaVersion", "eclipse-data-inspection/19");
        result.set("compositionalTemperatureContext", input.get("compositionalTemperatureContext").deepCopy()); return result;
    }

    private static JsonNode validateV18(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V3); fields.addAll(Set.of("simulation", "compositionalRestartContext"));
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (!EclipseCompositionalForecastParameters.validPortableContext(input.get("compositionalRestartContext"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalRestartContext"); compatible.put("schemaVersion", "eclipse-data-inspection/14");
        var result = (ObjectNode)validateV14(compatible, mapper); result.put("schemaVersion", "eclipse-data-inspection/18");
        result.set("compositionalRestartContext", input.get("compositionalRestartContext").deepCopy()); return result;
    }

    private static JsonNode validateV17(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V3);
        fields.addAll(Set.of("simulation", "compositionalProductionContext", "compositionalFluidPropertyContext"));
        if (input.has("compositionalReinjectionContext")) fields.add("compositionalReinjectionContext");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (!EclipseCompositionalFluidPropertyParameters.validContext(input.get("compositionalFluidPropertyContext"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalFluidPropertyContext");
        compatible.put("schemaVersion", input.has("compositionalReinjectionContext") ? "eclipse-data-inspection/16" : "eclipse-data-inspection/14");
        var result = (ObjectNode)(input.has("compositionalReinjectionContext") ? validateV16(compatible, mapper) : validateV14(compatible, mapper));
        result.put("schemaVersion", "eclipse-data-inspection/17"); result.set("compositionalFluidPropertyContext", input.get("compositionalFluidPropertyContext").deepCopy()); return result;
    }

    private static JsonNode validateV16(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V3);
        fields.addAll(Set.of("simulation", "compositionalProductionContext", "compositionalReinjectionContext"));
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (!EclipseCompositionalReinjectionParameters.validContext(input.get("compositionalReinjectionContext"), input)) fail();
        var compatible = input.deepCopy(); compatible.remove("compositionalReinjectionContext"); compatible.put("schemaVersion", "eclipse-data-inspection/14");
        var result = (ObjectNode) validateV14(compatible, mapper); result.put("schemaVersion", "eclipse-data-inspection/16");
        result.set("compositionalReinjectionContext", input.get("compositionalReinjectionContext").deepCopy());
        return result;
    }

    private static JsonNode validateV23(ObjectNode input, ObjectMapper mapper) {
        if (!EclipseThermalRockParameters.validContext(input.get("thermalRockContext"),input)) fail();
        var compatible=input.deepCopy();compatible.remove("thermalRockContext");compatible.put("schemaVersion","eclipse-data-inspection/15");
        var result=(ObjectNode)validateV15(compatible,mapper);result.put("schemaVersion","eclipse-data-inspection/23");
        result.set("thermalRockContext",input.get("thermalRockContext").deepCopy());return result;
    }
    private static JsonNode validateV15(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V3); fields.add("simulation");
        if (input.has("thermalInjectionContext")) fields.add("thermalInjectionContext");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (input.has("thermalInjectionContext") && !EclipseThermalInjectionParameters.validContext(input.get("thermalInjectionContext"), input)) fail();
        if (!EclipseThermalMetadataValidator.valid(input.get("simulation")) || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText()) || input.path("dimensions").isNull()) fail();
        var phases = input.path("phases");
        if (!phases.isArray() || phases.size() != 3 || !"OIL".equals(phases.get(0).asText()) || !"WATER".equals(phases.get(1).asText()) || !"GAS".equals(phases.get(2).asText())) fail();
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/15");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper, true));
        result.set("packageFiles", packageFiles(input.get("packageFiles"), mapper));
        result.set("simulation", input.get("simulation").deepCopy());
        if (input.has("thermalInjectionContext")) result.set("thermalInjectionContext", input.get("thermalInjectionContext").deepCopy());
        return result;
    }

    private static JsonNode validateV3(ObjectNode input, ObjectMapper mapper) {
        if (input.size() != ROOT_FIELDS_V3.size()) fail();
        input.properties().forEach(field -> { if (!ROOT_FIELDS_V3.contains(field.getKey())) fail(); });
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/3");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper));
        result.set("packageFiles", packageFiles(input.get("packageFiles"), mapper));
        return result;
    }

    private static JsonNode validateV4(ObjectNode input, ObjectMapper mapper) {
        boolean withWaterInjectionControlPlanContext = input.has("waterInjectionControlPlanContext");
        boolean withPackageControlPlanContext = input.has("packageProductionControlPlanContext");
        boolean withPackageProductionTargetContext = input.has("packageProductionTargetContext");
        boolean withNewWellContext = input.has("newWellContext");
        boolean withPvtwContext = input.has("pvtwContext");
        boolean withWetGasPvtContext = input.has("wetGasPvtContext");
        boolean withLiveOilPvtContext = input.has("liveOilPvtContext");
        boolean withOilPvtContext = input.has("oilPvtContext");
        boolean withGasPvtContext = input.has("gasPvtContext");
        boolean withMultiWaterPvtContext = input.has("multiWaterPvtContext");
        boolean withMultiWetGasPvtContext = input.has("multiWetGasPvtContext");
        boolean withMultiLiveOilPvtContext = input.has("multiLiveOilPvtContext");
        boolean withMultiPvtContext = input.has("multiPvtContext");
        boolean withMultiSatRelpermContext = input.has("multiSatRelpermContext");
        boolean withCoupledRelpermContext = input.has("coupledRelpermContext");
        boolean withRelpermContext = input.has("relpermContext");
        if (input.size() != ROOT_FIELDS_V4.size() + (withWaterInjectionControlPlanContext ? 1 : 0) + (withPackageControlPlanContext ? 1 : 0) + (withPackageProductionTargetContext ? 1 : 0) + (withNewWellContext ? 1 : 0) + (withPvtwContext ? 1 : 0) + (withWetGasPvtContext ? 1 : 0) + (withLiveOilPvtContext ? 1 : 0) + (withOilPvtContext ? 1 : 0) + (withGasPvtContext ? 1 : 0) + (withRelpermContext ? 1 : 0) + (withCoupledRelpermContext ? 1 : 0) + (withMultiSatRelpermContext ? 1 : 0) + (withMultiPvtContext ? 1 : 0) + (withMultiLiveOilPvtContext ? 1 : 0) + (withMultiWetGasPvtContext ? 1 : 0) + (withMultiWaterPvtContext ? 1 : 0)) fail();
        input.properties().forEach(field -> {
            if (!ROOT_FIELDS_V4.contains(field.getKey()) && !"waterInjectionControlPlanContext".equals(field.getKey()) && !"packageProductionControlPlanContext".equals(field.getKey()) && !"packageProductionTargetContext".equals(field.getKey()) && !"newWellContext".equals(field.getKey()) && !"pvtwContext".equals(field.getKey()) && !"wetGasPvtContext".equals(field.getKey()) && !"liveOilPvtContext".equals(field.getKey()) && !"oilPvtContext".equals(field.getKey()) && !"gasPvtContext".equals(field.getKey()) && !"relpermContext".equals(field.getKey()) && !"coupledRelpermContext".equals(field.getKey()) && !"multiSatRelpermContext".equals(field.getKey()) && !"multiPvtContext".equals(field.getKey()) && !"multiLiveOilPvtContext".equals(field.getKey()) && !"multiWetGasPvtContext".equals(field.getKey()) && !"multiWaterPvtContext".equals(field.getKey())) fail();
        });
        ObjectNode result = (ObjectNode) validateV1Fields(input, mapper, "eclipse-data-inspection/4");
        result.set("wellNames", wellNames(input.get("wellNames"), mapper));
        result.set("scheduleTimeline", scheduleTimeline(input.get("scheduleTimeline"), mapper, true));
        result.set("packageFiles", packageFiles(input.get("packageFiles"), mapper));
        result.set("scheduleMetadata", scheduleMetadata(input.get("scheduleMetadata"), mapper));
        if (withPackageControlPlanContext) {
            if (!EclipsePackageControlPlanParameters.validContext(input.get("packageProductionControlPlanContext"), result)) fail();
            result.set("packageProductionControlPlanContext", input.get("packageProductionControlPlanContext").deepCopy());
        }
        if (withWaterInjectionControlPlanContext) {
            if (!EclipseWaterInjectionPlanParameters.validContext(input.get("waterInjectionControlPlanContext"), result)) fail();
            result.set("waterInjectionControlPlanContext", input.get("waterInjectionControlPlanContext").deepCopy());
        }
        if (withPackageProductionTargetContext) {
            if (!EclipsePackageProductionTargetParameters.validContext(input.get("packageProductionTargetContext"), result)) fail();
            result.set("packageProductionTargetContext", input.get("packageProductionTargetContext").deepCopy());
        }
        if (withNewWellContext) result.set("newWellContext", newWellContext(input.get("newWellContext"), result));
        if (withPvtwContext) result.set("pvtwContext", pvtwContext(input.get("pvtwContext"), result));
        if (withWetGasPvtContext) result.set("wetGasPvtContext", wetGasPvtContext(input.get("wetGasPvtContext"), result));
        if (withLiveOilPvtContext) result.set("liveOilPvtContext", liveOilPvtContext(input.get("liveOilPvtContext"), result));
        if (withOilPvtContext) result.set("oilPvtContext", oilPvtContext(input.get("oilPvtContext"), result));
        if (withGasPvtContext) result.set("gasPvtContext", gasPvtContext(input.get("gasPvtContext"), result));
        if(withMultiLiveOilPvtContext){
            var c=input.get("multiLiveOilPvtContext");if(!EclipseMultiLiveOilPvtParameters.validContext(c,result))fail();
            int last=0;for(var r:c.path("regions")){if(!validSource(c.path("sourceFile").asText(),r.get("keywordLineNumber"))||!validSource(c.path("sourceFile").asText(),r.path("gasTable").get("keywordLineNumber")))fail();var records=r.path("records");var rows=records.path(records.size()-1).path("rows");last=Math.max(last,rows.path(rows.size()-1).path("lineNumber").asInt());}
            for(var event:result.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();result.set("multiLiveOilPvtContext",c.deepCopy());
        }
        if(withMultiWetGasPvtContext){
            var c=input.get("multiWetGasPvtContext");if(!EclipseMultiWetGasPvtParameters.validContext(c,result))fail();
            int last=0;for(var r:c.path("regions")){if(!validSource(c.path("sourceFile").asText(),r.get("keywordLineNumber"))||!validSource(c.path("sourceFile").asText(),r.path("oilTable").get("keywordLineNumber")))fail();var records=r.path("oilTable").path("records");var rows=records.path(records.size()-1).path("rows");last=Math.max(last,rows.path(rows.size()-1).path("lineNumber").asInt());}
            for(var event:result.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();result.set("multiWetGasPvtContext",c.deepCopy());
        }
        if (withMultiPvtContext) {
            var c=input.get("multiPvtContext");if(!EclipseMultiPvtParameters.validContext(c,result))fail();
            int last=0;for(var region:c.path("regions"))for(var t:region.path("tables")){if(!validSource(c.path("sourceFile").asText(),t.get("keywordLineNumber")))fail();last=Math.max(last,t.path("rows").path(t.path("rows").size()-1).path("lineNumber").asInt());}
            for(var event:result.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
            result.set("multiPvtContext",c.deepCopy());
        }
        if (withMultiSatRelpermContext) result.set("multiSatRelpermContext", multiSatRelpermContext(input.get("multiSatRelpermContext"), result));
        if (withMultiSatRelpermContext && withMultiPvtContext) {
            if (!Set.of("eclipse-multi-sat-relperm-context/1","eclipse-multi-sat-relperm-context/2").contains(result.path("multiSatRelpermContext").path("schemaVersion").asText())) fail();
            var satLines = new java.util.HashSet<Integer>();
            for (var region : result.path("multiSatRelpermContext").path("regions")) for (var table : region.path("tables")) {
                satLines.add(table.path("keywordLineNumber").asInt());
                for (var row : table.path("rows")) satLines.add(row.path("lineNumber").asInt());
            }
            for (var region : result.path("multiPvtContext").path("regions")) for (var table : region.path("tables")) {
                if (satLines.contains(table.path("keywordLineNumber").asInt())) fail();
                for (var row : table.path("rows")) if (satLines.contains(row.path("lineNumber").asInt())) fail();
            }
        }
        if (withMultiSatRelpermContext && withMultiLiveOilPvtContext) {
            if(!java.util.Set.of("eclipse-multi-sat-relperm-context/1","eclipse-multi-sat-relperm-context/2").contains(result.path("multiSatRelpermContext").path("schemaVersion").asText()))fail();
            var satLines=new java.util.HashSet<Integer>();for(var r:result.path("multiSatRelpermContext").path("regions"))for(var t:r.path("tables")){satLines.add(t.path("keywordLineNumber").asInt());for(var row:t.path("rows"))satLines.add(row.path("lineNumber").asInt());}
            for(var r:result.path("multiLiveOilPvtContext").path("regions")){
                if(satLines.contains(r.path("keywordLineNumber").asInt())||satLines.contains(r.path("gasTable").path("keywordLineNumber").asInt()))fail();
                for(var record:r.path("records"))for(var row:record.path("rows"))if(satLines.contains(row.path("lineNumber").asInt()))fail();
                for(var row:r.path("gasTable").path("rows"))if(satLines.contains(row.path("lineNumber").asInt()))fail();
            }
        }
        if (withMultiSatRelpermContext && withMultiWetGasPvtContext) {
            if(!java.util.Set.of("eclipse-multi-sat-relperm-context/1","eclipse-multi-sat-relperm-context/2").contains(result.path("multiSatRelpermContext").path("schemaVersion").asText()))fail();
            var satLines=new java.util.HashSet<Integer>();for(var r:result.path("multiSatRelpermContext").path("regions"))for(var t:r.path("tables")){satLines.add(t.path("keywordLineNumber").asInt());for(var row:t.path("rows"))satLines.add(row.path("lineNumber").asInt());}
            for(var r:result.path("multiWetGasPvtContext").path("regions")){
                if(satLines.contains(r.path("keywordLineNumber").asInt())||satLines.contains(r.path("oilTable").path("keywordLineNumber").asInt()))fail();
                for(var record:r.path("records"))for(var row:record.path("rows"))if(satLines.contains(row.path("lineNumber").asInt()))fail();
                for(var record:r.path("oilTable").path("records"))for(var row:record.path("rows"))if(satLines.contains(row.path("lineNumber").asInt()))fail();
            }
        }
        if (withMultiWaterPvtContext) {
            var c=input.get("multiWaterPvtContext");if(!EclipseMultiWaterPvtParameters.validContext(c,result))fail();
            for(var r:c.path("regions")){if(!validSource(c.path("sourceFile").asText(),r.get("lineNumber")))fail();for(var event:result.path("scheduleTimeline"))if(event.path("lineNumber").asInt()<=r.path("lineNumber").asInt())fail();}
            result.set("multiWaterPvtContext",c.deepCopy());
        }
        if (withCoupledRelpermContext) result.set("coupledRelpermContext", coupledRelpermContext(input.get("coupledRelpermContext"), result));
        if (withRelpermContext) result.set("relpermContext", relpermContext(input.get("relpermContext"), result));
        return result;
    }

    private static JsonNode relpermContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","tables");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());
        if(!c.path("schemaVersion").isString()||!"eclipse-relperm-context/1".equals(c.path("schemaVersion").asText())||!c.path("sourceFile").isString()||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")
            ||!phases.equals(Set.of("OIL","GAS","WATER"))||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))||!c.path("sourceFile").equals(inspection.path("caseName"))||!EclipseRelpermParameters.validTables(c.path("tables")))fail();
        int last=0;
        for(var t:c.path("tables")){
            if(!validSource(c.path("sourceFile").asText(),t.get("keywordLineNumber"))||t.path("keywordLineNumber").asInt()<=last)fail();
            last=t.path("rows").path(t.path("rows").size()-1).path("lineNumber").asInt();
        }
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode multiSatRelpermContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","regionCount","regions");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());long cells=1;
        for(var axis:new String[]{"nx","ny","nz"}){var n=inspection.path("dimensions").path(axis);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<1||n.asInt()>2000000)fail();cells*=n.asInt();if(cells>2000000)fail();}
        if(!c.path("schemaVersion").isString()||!Set.of("eclipse-multi-sat-relperm-context/1","eclipse-multi-sat-relperm-context/2").contains(c.path("schemaVersion").asText())||!c.path("sourceFile").isString()||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")
            ||!phases.equals(Set.of("OIL","GAS","WATER"))||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))||!c.path("sourceFile").equals(inspection.path("caseName"))
            ||!c.path("regionCount").isIntegralNumber()||!c.path("regionCount").canConvertToInt()||!EclipseMultiSatRelpermParameters.validRegions(c.path("regions"),c.path("regionCount").asInt(),cells,"eclipse-multi-sat-relperm-context/2".equals(c.path("schemaVersion").asText())))fail();
        int last=0;for(var region:c.path("regions"))for(var t:region.path("tables")){if(!validSource(c.path("sourceFile").asText(),t.get("keywordLineNumber")))fail();last=Math.max(last,t.path("rows").path(t.path("rows").size()-1).path("lineNumber").asInt());}
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode coupledRelpermContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","tables");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());
        if(!c.path("schemaVersion").isString()||!"eclipse-coupled-relperm-context/1".equals(c.path("schemaVersion").asText())||!c.path("sourceFile").isString()||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$")
            ||!phases.equals(Set.of("OIL","GAS","WATER"))||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))||!c.path("sourceFile").equals(inspection.path("caseName"))||!EclipseCoupledRelpermParameters.validTables(c.path("tables")))fail();
        int last=0;
        for(var t:c.path("tables")){
            if(!validSource(c.path("sourceFile").asText(),t.get("keywordLineNumber"))||t.path("keywordLineNumber").asInt()<=last)fail();
            last=t.path("rows").path(t.path("rows").size()-1).path("lineNumber").asInt();
        }
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode gasPvtContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","keywordLineNumber","rows");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        boolean gas=false;for(var phase:inspection.path("phases"))gas|="GAS".equals(phase.asText());
        if(!c.path("schemaVersion").isString()||!"eclipse-gas-pvt-context/1".equals(c.path("schemaVersion").asText())
            ||!c.path("sourceFile").isString()||!validSource(c.path("sourceFile").asText(),c.get("keywordLineNumber"))
            ||!EclipseGasPvtParameters.validRows(c.path("rows"),c.path("keywordLineNumber").asInt())||!gas
            ||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))
            ||!c.path("sourceFile").equals(inspection.path("caseName"))||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$"))fail();
        int last=c.path("rows").path(c.path("rows").size()-1).path("lineNumber").asInt();
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode wetGasPvtContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","keywordLineNumber","records");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());
        if(!c.path("schemaVersion").isString()||!"eclipse-wet-gas-pvt-context/1".equals(c.path("schemaVersion").asText())
            ||!c.path("sourceFile").isString()||!validSource(c.path("sourceFile").asText(),c.get("keywordLineNumber"))
            ||!EclipseWetGasPvtParameters.validRecords(c.path("records"),c.path("keywordLineNumber").asInt())||!phases.containsAll(Set.of("OIL","GAS"))
            ||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))
            ||!c.path("sourceFile").equals(inspection.path("caseName"))||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$"))fail();
        var rows=c.path("records").path(c.path("records").size()-1).path("rows");
        int last=rows.path(rows.size()-1).path("lineNumber").asInt();
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode liveOilPvtContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","keywordLineNumber","records");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        var phases=new HashSet<String>();for(var phase:inspection.path("phases"))phases.add(phase.asText());
        if(!c.path("schemaVersion").isString()||!"eclipse-live-oil-pvt-context/1".equals(c.path("schemaVersion").asText())
            ||!c.path("sourceFile").isString()||!validSource(c.path("sourceFile").asText(),c.get("keywordLineNumber"))
            ||!EclipseLiveOilPvtParameters.validRecords(c.path("records"),c.path("keywordLineNumber").asInt())||!phases.containsAll(Set.of("OIL","GAS"))
            ||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))
            ||!c.path("sourceFile").equals(inspection.path("caseName"))||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$"))fail();
        var rows=c.path("records").path(c.path("records").size()-1).path("rows");
        int last=rows.path(rows.size()-1).path("lineNumber").asInt();
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode oilPvtContext(JsonNode c,ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","keywordLineNumber","rows");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        boolean oil=false;for(var phase:inspection.path("phases"))oil|="OIL".equals(phase.asText());
        if(!c.path("schemaVersion").isString()||!"eclipse-oil-pvt-context/1".equals(c.path("schemaVersion").asText())
            ||!c.path("sourceFile").isString()||!validSource(c.path("sourceFile").asText(),c.get("keywordLineNumber"))
            ||!EclipseOilPvtParameters.validRows(c.path("rows"),c.path("keywordLineNumber").asInt())||!oil
            ||!UNIT_SYSTEMS.contains(inspection.path("unitSystem").asText())||inspection.path("packageFiles").size()!=1
            ||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))
            ||!c.path("sourceFile").equals(inspection.path("caseName"))||!c.path("sourceFile").asText().matches("(?i)^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.DATA$"))fail();
        int last=c.path("rows").path(c.path("rows").size()-1).path("lineNumber").asInt();
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=last)fail();
        return c.deepCopy();
    }

    private static JsonNode pvtwContext(JsonNode c, ObjectNode inspection) {
        var fields=Set.of("schemaVersion","sourceFile","lineNumber","recordValues");
        if(c==null||!c.isObject()||c.size()!=fields.size())fail();
        c.properties().forEach(p->{if(!fields.contains(p.getKey()))fail();});
        if(!c.path("schemaVersion").isString()||!"eclipse-pvtw-context/1".equals(c.path("schemaVersion").asText())
            ||!c.path("sourceFile").isString()||!validSource(c.path("sourceFile").asText(),c.get("lineNumber"))
            ||!EclipsePvtwParameters.validRow(c.path("recordValues"))||inspection.path("phases").size()!=1||!"WATER".equals(inspection.path("phases").path(0).asText())
            ||inspection.path("packageFiles").size()!=1||!c.path("sourceFile").equals(inspection.path("packageFiles").path(0).path("relativePath"))
            ||!c.path("sourceFile").equals(inspection.path("caseName"))||c.path("sourceFile").asText().contains("/"))fail();
        for(var event:inspection.path("scheduleTimeline"))if(!event.path("sourceFile").equals(c.path("sourceFile"))||event.path("lineNumber").asInt()<=c.path("lineNumber").asInt())fail();
        return c.deepCopy();
    }

    private static JsonNode newWellContext(JsonNode context, ObjectNode inspection) {
        var fields = Set.of("schemaVersion", "sourceFile", "insertionLine", "maxWells", "maxConnections", "maxGroups", "maxWellsPerGroup");
        if (context == null || !context.isObject() || context.size() != fields.size()) fail();
        context.properties().forEach(field -> { if (!fields.contains(field.getKey())) fail(); });
        if (!context.path("schemaVersion").isTextual() || !"eclipse-new-well-context/1".equals(context.path("schemaVersion").asText())
                || !context.path("sourceFile").isTextual() || !validSource(context.path("sourceFile").asText(), context.get("insertionLine"))) fail();
        String source = context.path("sourceFile").asText();
        int insertion = context.path("insertionLine").asInt();
        for (String field : List.of("maxWells", "maxConnections", "maxGroups", "maxWellsPerGroup")) {
            var capacity = context.path(field);
            if (!capacity.isIntegralNumber() || !capacity.canConvertToInt() || capacity.asInt() < 1 || capacity.asInt() > 100_000) fail();
        }
        if (source.contains("/") || !source.toUpperCase(Locale.ROOT).endsWith(".DATA") || !source.equals(inspection.path("caseName").asText())
                || inspection.path("dimensions").isNull() || inspection.path("phases").size() != 1 || !"WATER".equals(inspection.path("phases").get(0).asText())
                || inspection.path("packageFiles").size() != 1 || !source.equals(inspection.path("packageFiles").get(0).path("relativePath").asText())
                || inspection.path("scheduleTimeline").size() != 1 || !"TSTEP".equals(inspection.path("scheduleTimeline").get(0).path("kind").asText())
                || !source.equals(inspection.path("scheduleTimeline").get(0).path("sourceFile").asText())
                || insertion != inspection.path("scheduleTimeline").get(0).path("lineNumber").asInt()) fail();
        var metadata = inspection.path("scheduleMetadata");
        if (!metadata.path("groups").isEmpty() || metadata.path("wells").isEmpty()
                || metadata.path("wells").size() > context.path("maxWells").asInt()) fail();
        var groupCounts = new java.util.HashMap<String, Integer>();
        for (var well : metadata.path("wells")) {
            if (well.path("group").isNull() || well.path("group").asText().isBlank()) fail();
            String group = well.path("group").asText();
            groupCounts.merge(group, 1, Integer::sum);
        }
        if (groupCounts.size() > context.path("maxGroups").asInt()
                || groupCounts.values().stream().anyMatch(count -> count > context.path("maxWellsPerGroup").asInt())) fail();
        for (String collection : List.of("wells", "records", "completions")) for (var record : metadata.path(collection)) {
            if (!source.equals(record.path("sourceFile").asText()) || record.path("lineNumber").asInt() >= insertion) fail();
        }
        return context.deepCopy();
    }

    private static JsonNode validateV5(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var v4 = input.deepCopy(); v4.remove("vfpTables"); v4.put("schemaVersion", "eclipse-data-inspection/4");
        var result = (ObjectNode) validateV4(v4, mapper);
        var tables = input.path("vfpTables");
        var tableFields = Set.of("number", "datumDepth", "flowType", "waterFractionType", "gasFractionType", "alqType", "unitSystem", "sourceFile", "lineNumber", "sha256", "flowAxis", "thpAxis", "waterFractionAxis", "gasFractionAxis", "alqAxis");
        if (!tables.isArray() || tables.isEmpty() || tables.size() > 64) fail();
        var ids = new HashSet<Integer>();
        for (var t : tables) {
            if (!t.isObject() || t.size() != tableFields.size()) fail();
            t.properties().forEach(f -> { if (!tableFields.contains(f.getKey())) fail(); });
            if (!t.path("number").isIntegralNumber() || !t.path("number").canConvertToInt() || t.path("number").asInt() < 1 || t.path("number").asInt() > 100000 || !ids.add(t.path("number").asInt())
                || !t.path("datumDepth").isNumber() || !Double.isFinite(t.path("datumDepth").asDouble()) || t.path("datumDepth").asDouble() < 0
                || !Set.of("OIL", "LIQ", "GAS").contains(t.path("flowType").asText()) || !Set.of("WOR", "WCT", "WGR").contains(t.path("waterFractionType").asText())
                || !Set.of("GOR", "GLR", "OGR").contains(t.path("gasFractionType").asText()) || !t.path("alqType").isTextual() || t.path("alqType").asText().length() > 16
                || !Set.of("FIELD", "METRIC").contains(t.path("unitSystem").asText()) || !t.path("unitSystem").asText().equals(input.path("unitSystem").asText())
                || !validSource(t.path("sourceFile").asText(), t.path("lineNumber")) || !SHA256.matcher(t.path("sha256").asText()).matches()) fail();
            boolean bound = false;
            for (var file : result.path("packageFiles")) if (file.path("relativePath").asText().equals(t.path("sourceFile").asText())) bound = true;
            if (!bound) fail();
            for (var key : List.of("flowAxis", "thpAxis", "waterFractionAxis", "gasFractionAxis", "alqAxis")) {
                var axis = t.path(key); int min = key.equals("flowAxis") ? 2 : 1, max = key.equals("flowAxis") ? 128 : 32;
                if (!axis.isArray() || axis.size() < min || axis.size() > max) fail();
                double last = -1;
                for (var v : axis) { if (!v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble() < 0 || v.asDouble() <= last) fail(); last = v.asDouble(); }
            }
        }
        result.put("schemaVersion", "eclipse-data-inspection/5"); result.set("vfpTables", tables);
        return result;
    }

    private static JsonNode validateV13(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("productionControlPlan");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (!input.path("vfpTables").isArray() || !input.path("vfpTables").isEmpty()) fail();
        var old = input.deepCopy(); old.remove("productionControlPlan"); old.remove("vfpTables");
        old.put("schemaVersion", "eclipse-data-inspection/4");
        var result = (ObjectNode) validateV4(old, mapper);
        if (!EclipseControlPlanParameters.validPlan(input)) fail();
        result.put("schemaVersion", "eclipse-data-inspection/13");
        result.set("vfpTables", mapper.createArrayNode()); result.set("productionControlPlan", input.path("productionControlPlan"));
        return result;
    }

    private static JsonNode validateV12(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("groupStructureControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        if (!input.path("vfpTables").isArray() || !input.path("vfpTables").isEmpty()) fail();
        var old = input.deepCopy(); old.remove("groupStructureControls"); old.remove("vfpTables");
        old.put("schemaVersion", "eclipse-data-inspection/4");
        var result = (ObjectNode) validateV4(old, mapper);
        if (!EclipseGroupStructureParameters.validControls(input)) fail();
        result.put("schemaVersion", "eclipse-data-inspection/12");
        result.set("vfpTables", mapper.createArrayNode()); result.set("groupStructureControls", input.path("groupStructureControls"));
        return result;
    }

    private static JsonNode validateV11(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("advancedInjectionGroupControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var old = input.deepCopy(); old.remove("advancedInjectionGroupControls");
        if (!old.path("vfpTables").isArray()) fail();
        ObjectNode result;
        if (old.path("vfpTables").isEmpty()) {
            old.remove("vfpTables"); old.put("schemaVersion", "eclipse-data-inspection/4");
            result = (ObjectNode) validateV4(old, mapper); result.set("vfpTables", mapper.createArrayNode());
        } else {
            old.put("schemaVersion", "eclipse-data-inspection/5"); result = (ObjectNode) validateV5(old, mapper);
        }
        var controls = input.path("advancedInjectionGroupControls");
        if (!controls.isArray() || controls.isEmpty() || controls.size() > 1000 || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText())
            || result.path("packageFiles").size() != 1 || !result.path("scheduleMetadata").path("groups").isEmpty()) fail();
        var groups = new HashSet<String>();
        var controlFields = Set.of("group", "sourceFile", "lineNumber", "controlMode", "unit", "originalTargetRate", "members", "injectionPhase", "referenceGroup", "referenceMembers");
        for (var g : controls) {
            String mode = g.path("controlMode").asText();
            String phase = g.path("injectionPhase").asText();
            String unit = EclipseAdvancedInjectionParameters.unit(input.path("unitSystem").asText(), phase, mode);
            if (!g.isObject() || g.size() != controlFields.size()) fail();
            g.properties().forEach(f -> { if (!controlFields.contains(f.getKey())) fail(); });
            if (!g.path("group").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}", g.path("group").asText())
                || "FIELD".equals(g.path("group").asText()) || !groups.add(g.path("group").asText())
                || !validSource(g.path("sourceFile").asText(), g.path("lineNumber")) || !g.path("sourceFile").asText().equals(input.path("caseName").asText())
                || !g.path("sourceFile").asText().equals(result.path("packageFiles").get(0).path("relativePath").asText())
                || unit == null || !unit.equals(g.path("unit").asText())
                || !g.path("originalTargetRate").isNumber() || !Double.isFinite(g.path("originalTargetRate").asDouble()) || g.path("originalTargetRate").asDouble() <= 0 || g.path("originalTargetRate").asDouble() > 100_000_000
                || !g.path("members").isArray() || g.path("members").size() < 2 || g.path("members").size() > 1000) fail();
            var members = new HashSet<String>();
            for (var m : g.path("members")) {
                if (!m.isObject() || m.size() != 3 || !m.has("well") || !m.has("bhpLimit") || !m.has("surfaceRateLimit")
                    || !m.path("surfaceRateLimit").isNumber() || !Double.isFinite(m.path("surfaceRateLimit").asDouble()) || m.path("surfaceRateLimit").asDouble() <= 0 || m.path("surfaceRateLimit").asDouble() > 100_000_000 || !m.path("well").isTextual() || !members.add(m.path("well").asText())
                    || !m.path("bhpLimit").isNumber() || !Double.isFinite(m.path("bhpLimit").asDouble()) || m.path("bhpLimit").asDouble() <= 0 || m.path("bhpLimit").asDouble() > 100_000_000) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (m.path("well").asText().equals(w.path("name").asText()) && g.path("group").asText().equals(w.path("group").asText())) found++;
                if (found != 1) fail();
            }
            int direct = 0;
            for (var w : result.path("scheduleMetadata").path("wells")) if (g.path("group").asText().equals(w.path("group").asText())) direct++;
            if (direct != members.size()) fail();
            boolean ratio = Set.of("REIN", "VREP").contains(mode);
            var refGroup = g.path("referenceGroup"); var refMembers = g.path("referenceMembers");
            if (!refMembers.isArray() || (ratio ? !refGroup.isTextual() || !validWellName(refGroup.asText()) || "FIELD".equals(refGroup.asText()) || refGroup.equals(g.path("group")) || refMembers.isEmpty() || refMembers.size() > 1000 : !refGroup.isNull() || !refMembers.isEmpty())) fail();
            var refs = new HashSet<String>();
            for (var ref : refMembers) {
                if (!ref.isTextual() || !validWellName(ref.asText()) || !refs.add(ref.asText())) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (ref.equals(w.path("name")) && refGroup.equals(w.path("group"))) found++;
                if (found != 1 || members.contains(ref.asText())) fail();
            }
            int refCount = 0;
            if (ratio) for (var w : result.path("scheduleMetadata").path("wells")) if (refGroup.equals(w.path("group"))) refCount++;
            if (refCount != refs.size()) fail();
            for (var e : result.path("scheduleTimeline")) if (g.path("lineNumber").asInt() >= e.path("lineNumber").asInt()) fail();
        }
        result.put("schemaVersion", "eclipse-data-inspection/11"); result.set("advancedInjectionGroupControls", controls); return result;
    }

    private static JsonNode validateV10(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("waterProductionGroupControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var old = input.deepCopy(); old.remove("waterProductionGroupControls");
        if (!old.path("vfpTables").isArray()) fail();
        ObjectNode result;
        if (old.path("vfpTables").isEmpty()) {
            old.remove("vfpTables"); old.put("schemaVersion", "eclipse-data-inspection/4");
            result = (ObjectNode) validateV4(old, mapper); result.set("vfpTables", mapper.createArrayNode());
        } else {
            old.put("schemaVersion", "eclipse-data-inspection/5"); result = (ObjectNode) validateV5(old, mapper);
        }
        var controls = input.path("waterProductionGroupControls");
        if (!controls.isArray() || controls.isEmpty() || controls.size() > 1000 || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText())
            || result.path("packageFiles").size() != 1 || !result.path("scheduleMetadata").path("groups").isEmpty()) fail();
        var groups = new HashSet<String>();
        var controlFields = Set.of("group", "sourceFile", "lineNumber", "controlMode", "unit", "originalTargetRate", "members");
        for (var g : controls) {
            String mode = g.path("controlMode").asText();
            String unit = "FIELD".equals(input.path("unitSystem").asText()) ? "STB/day" : "sm3/day";
            if (!g.isObject() || g.size() != controlFields.size()) fail();
            g.properties().forEach(f -> { if (!controlFields.contains(f.getKey())) fail(); });
            if (!g.path("group").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}", g.path("group").asText())
                || "FIELD".equals(g.path("group").asText()) || !groups.add(g.path("group").asText())
                || !validSource(g.path("sourceFile").asText(), g.path("lineNumber")) || !g.path("sourceFile").asText().equals(input.path("caseName").asText())
                || !g.path("sourceFile").asText().equals(result.path("packageFiles").get(0).path("relativePath").asText())
                || !"WRAT".equals(mode) || !unit.equals(g.path("unit").asText())
                || !g.path("originalTargetRate").isNumber() || !Double.isFinite(g.path("originalTargetRate").asDouble()) || g.path("originalTargetRate").asDouble() <= 0 || g.path("originalTargetRate").asDouble() > 100_000_000
                || !g.path("members").isArray() || g.path("members").size() < 2 || g.path("members").size() > 1000) fail();
            var members = new HashSet<String>();
            for (var m : g.path("members")) {
                if (!m.isObject() || m.size() != 3 || !m.has("well") || !m.has("bhpLimit") || !m.has("oilRateLimit")
                    || !m.path("oilRateLimit").isNumber() || !Double.isFinite(m.path("oilRateLimit").asDouble()) || m.path("oilRateLimit").asDouble() <= 0 || m.path("oilRateLimit").asDouble() > 100_000_000 || !m.path("well").isTextual() || !members.add(m.path("well").asText())
                    || !m.path("bhpLimit").isNumber() || !Double.isFinite(m.path("bhpLimit").asDouble()) || m.path("bhpLimit").asDouble() <= 0 || m.path("bhpLimit").asDouble() > 100_000_000) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (m.path("well").asText().equals(w.path("name").asText()) && g.path("group").asText().equals(w.path("group").asText())) found++;
                if (found != 1) fail();
            }
            int direct = 0;
            for (var w : result.path("scheduleMetadata").path("wells")) if (g.path("group").asText().equals(w.path("group").asText())) direct++;
            if (direct != members.size()) fail();
            for (var e : result.path("scheduleTimeline")) if (g.path("lineNumber").asInt() >= e.path("lineNumber").asInt()) fail();
        }
        result.put("schemaVersion", "eclipse-data-inspection/10"); result.set("waterProductionGroupControls", controls); return result;
    }

    private static JsonNode validateV9(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("groupRateControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var old = input.deepCopy(); old.remove("groupRateControls");
        if (!old.path("vfpTables").isArray()) fail();
        ObjectNode result;
        if (old.path("vfpTables").isEmpty()) {
            old.remove("vfpTables"); old.put("schemaVersion", "eclipse-data-inspection/4");
            result = (ObjectNode) validateV4(old, mapper); result.set("vfpTables", mapper.createArrayNode());
        } else {
            old.put("schemaVersion", "eclipse-data-inspection/5"); result = (ObjectNode) validateV5(old, mapper);
        }
        var controls = input.path("groupRateControls");
        if (!controls.isArray() || controls.isEmpty() || controls.size() > 1000 || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText())
            || result.path("packageFiles").size() != 1 || !result.path("scheduleMetadata").path("groups").isEmpty()) fail();
        var groups = new HashSet<String>();
        var controlFields = Set.of("group", "sourceFile", "lineNumber", "controlMode", "unit", "originalTargetRate", "members");
        for (var g : controls) {
            String mode = g.path("controlMode").asText();
            String unit = "FIELD".equals(input.path("unitSystem").asText()) ? "GRAT".equals(mode) ? "Mscf/day" : "STB/day" : "sm3/day";
            if (!g.isObject() || g.size() != controlFields.size()) fail();
            g.properties().forEach(f -> { if (!controlFields.contains(f.getKey())) fail(); });
            if (!g.path("group").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}", g.path("group").asText())
                || "FIELD".equals(g.path("group").asText()) || !groups.add(g.path("group").asText())
                || !validSource(g.path("sourceFile").asText(), g.path("lineNumber")) || !g.path("sourceFile").asText().equals(input.path("caseName").asText())
                || !g.path("sourceFile").asText().equals(result.path("packageFiles").get(0).path("relativePath").asText())
                || !Set.of("GRAT", "LRAT").contains(mode) || !unit.equals(g.path("unit").asText())
                || !g.path("originalTargetRate").isNumber() || !Double.isFinite(g.path("originalTargetRate").asDouble()) || g.path("originalTargetRate").asDouble() <= 0 || g.path("originalTargetRate").asDouble() > 100_000_000
                || !g.path("members").isArray() || g.path("members").size() < 2 || g.path("members").size() > 1000) fail();
            var members = new HashSet<String>();
            for (var m : g.path("members")) {
                if (!m.isObject() || m.size() != 2 || !m.has("well") || !m.has("bhpLimit") || !m.path("well").isTextual() || !members.add(m.path("well").asText())
                    || !m.path("bhpLimit").isNumber() || !Double.isFinite(m.path("bhpLimit").asDouble()) || m.path("bhpLimit").asDouble() <= 0 || m.path("bhpLimit").asDouble() > 100_000_000) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (m.path("well").asText().equals(w.path("name").asText()) && g.path("group").asText().equals(w.path("group").asText())) found++;
                if (found != 1) fail();
            }
            int direct = 0;
            for (var w : result.path("scheduleMetadata").path("wells")) if (g.path("group").asText().equals(w.path("group").asText())) direct++;
            if (direct != members.size()) fail();
            for (var e : result.path("scheduleTimeline")) if (g.path("lineNumber").asInt() >= e.path("lineNumber").asInt()) fail();
        }
        result.put("schemaVersion", "eclipse-data-inspection/9"); result.set("groupRateControls", controls); return result;
    }

    private static JsonNode validateV6(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("groupControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var old = input.deepCopy(); old.remove("groupControls");
        if (!old.path("vfpTables").isArray()) fail();
        ObjectNode result;
        if (old.path("vfpTables").isEmpty()) {
            old.remove("vfpTables"); old.put("schemaVersion", "eclipse-data-inspection/4");
            result = (ObjectNode) validateV4(old, mapper); result.set("vfpTables", mapper.createArrayNode());
        } else {
            old.put("schemaVersion", "eclipse-data-inspection/5"); result = (ObjectNode) validateV5(old, mapper);
        }
        var controls = input.path("groupControls");
        if (!controls.isArray() || controls.isEmpty() || controls.size() > 1000 || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText())
            || result.path("packageFiles").size() != 1 || !result.path("scheduleMetadata").path("groups").isEmpty()) fail();
        var groups = new HashSet<String>();
        var controlFields = Set.of("group", "sourceFile", "lineNumber", "controlMode", "unit", "originalTargetRate", "members");
        String unit = "FIELD".equals(input.path("unitSystem").asText()) ? "STB/day" : "sm3/day";
        for (var g : controls) {
            if (!g.isObject() || g.size() != controlFields.size()) fail();
            g.properties().forEach(f -> { if (!controlFields.contains(f.getKey())) fail(); });
            if (!g.path("group").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}", g.path("group").asText())
                || "FIELD".equals(g.path("group").asText()) || !groups.add(g.path("group").asText())
                || !validSource(g.path("sourceFile").asText(), g.path("lineNumber")) || !g.path("sourceFile").asText().equals(input.path("caseName").asText())
                || !g.path("sourceFile").asText().equals(result.path("packageFiles").get(0).path("relativePath").asText())
                || !"ORAT".equals(g.path("controlMode").asText()) || !unit.equals(g.path("unit").asText())
                || !g.path("originalTargetRate").isNumber() || !Double.isFinite(g.path("originalTargetRate").asDouble()) || g.path("originalTargetRate").asDouble() <= 0 || g.path("originalTargetRate").asDouble() > 100_000_000
                || !g.path("members").isArray() || g.path("members").size() < 2 || g.path("members").size() > 1000) fail();
            var members = new HashSet<String>();
            for (var m : g.path("members")) {
                if (!m.isObject() || m.size() != 2 || !m.has("well") || !m.has("bhpLimit") || !m.path("well").isTextual() || !members.add(m.path("well").asText())
                    || !m.path("bhpLimit").isNumber() || !Double.isFinite(m.path("bhpLimit").asDouble()) || m.path("bhpLimit").asDouble() <= 0 || m.path("bhpLimit").asDouble() > 100_000_000) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (m.path("well").asText().equals(w.path("name").asText()) && g.path("group").asText().equals(w.path("group").asText())) found++;
                if (found != 1) fail();
            }
            int direct = 0;
            for (var w : result.path("scheduleMetadata").path("wells")) if (g.path("group").asText().equals(w.path("group").asText())) direct++;
            if (direct != members.size()) fail();
            for (var e : result.path("scheduleTimeline")) if (g.path("lineNumber").asInt() >= e.path("lineNumber").asInt()) fail();
        }
        result.put("schemaVersion", "eclipse-data-inspection/6"); result.set("groupControls", controls); return result;
    }

    private static JsonNode validateV7(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("injectionGroupControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var old = input.deepCopy(); old.remove("injectionGroupControls");
        if (!old.path("vfpTables").isArray()) fail();
        ObjectNode result;
        if (old.path("vfpTables").isEmpty()) {
            old.remove("vfpTables"); old.put("schemaVersion", "eclipse-data-inspection/4");
            result = (ObjectNode) validateV4(old, mapper); result.set("vfpTables", mapper.createArrayNode());
        } else {
            old.put("schemaVersion", "eclipse-data-inspection/5"); result = (ObjectNode) validateV5(old, mapper);
        }
        var controls = input.path("injectionGroupControls");
        if (!controls.isArray() || controls.isEmpty() || controls.size() > 1000 || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText())
            || result.path("packageFiles").size() != 1 || !result.path("scheduleMetadata").path("groups").isEmpty()) fail();
        var groups = new HashSet<String>();
        var controlFields = Set.of("group", "sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "originalTargetRate", "members");

        for (var g : controls) {
            String phase = g.path("injectionPhase").asText();
            String unit = "FIELD".equals(input.path("unitSystem").asText()) ? phase.equals("GAS") ? "Mscf/day" : "STB/day" : "sm3/day";
            if (!g.isObject() || g.size() != controlFields.size()) fail();
            g.properties().forEach(f -> { if (!controlFields.contains(f.getKey())) fail(); });
            if (!g.path("group").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}", g.path("group").asText())
                || "FIELD".equals(g.path("group").asText()) || !groups.add(g.path("group").asText())
                || !validSource(g.path("sourceFile").asText(), g.path("lineNumber")) || !g.path("sourceFile").asText().equals(input.path("caseName").asText())
                || !g.path("sourceFile").asText().equals(result.path("packageFiles").get(0).path("relativePath").asText())
                || !Set.of("WATER").contains(phase) || !"RATE".equals(g.path("controlMode").asText()) || !unit.equals(g.path("unit").asText())
                || !g.path("originalTargetRate").isNumber() || !Double.isFinite(g.path("originalTargetRate").asDouble()) || g.path("originalTargetRate").asDouble() <= 0 || g.path("originalTargetRate").asDouble() > 100_000_000
                || !g.path("members").isArray() || g.path("members").size() < 2 || g.path("members").size() > 1000) fail();
            var members = new HashSet<String>();
            for (var m : g.path("members")) {
                if (!m.isObject() || m.size() != 2 || !m.has("well") || !m.has("bhpLimit") || !m.path("well").isTextual() || !members.add(m.path("well").asText())
                    || !m.path("bhpLimit").isNumber() || !Double.isFinite(m.path("bhpLimit").asDouble()) || m.path("bhpLimit").asDouble() <= 0 || m.path("bhpLimit").asDouble() > 100_000_000) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (m.path("well").asText().equals(w.path("name").asText()) && g.path("group").asText().equals(w.path("group").asText())) found++;
                if (found != 1) fail();
            }
            int direct = 0;
            for (var w : result.path("scheduleMetadata").path("wells")) if (g.path("group").asText().equals(w.path("group").asText())) direct++;
            if (direct != members.size()) fail();
            for (var e : result.path("scheduleTimeline")) if (g.path("lineNumber").asInt() >= e.path("lineNumber").asInt()) fail();
        }
        result.put("schemaVersion", "eclipse-data-inspection/7"); result.set("injectionGroupControls", controls); return result;
    }

    private static JsonNode validateV8(ObjectNode input, ObjectMapper mapper) {
        var fields = new HashSet<>(ROOT_FIELDS_V4); fields.add("vfpTables"); fields.add("gasInjectionGroupControls");
        if (input.size() != fields.size()) fail();
        input.properties().forEach(f -> { if (!fields.contains(f.getKey())) fail(); });
        var old = input.deepCopy(); old.remove("gasInjectionGroupControls");
        if (!old.path("vfpTables").isArray()) fail();
        ObjectNode result;
        if (old.path("vfpTables").isEmpty()) {
            old.remove("vfpTables"); old.put("schemaVersion", "eclipse-data-inspection/4");
            result = (ObjectNode) validateV4(old, mapper); result.set("vfpTables", mapper.createArrayNode());
        } else {
            old.put("schemaVersion", "eclipse-data-inspection/5"); result = (ObjectNode) validateV5(old, mapper);
        }
        var controls = input.path("gasInjectionGroupControls");
        if (!controls.isArray() || controls.isEmpty() || controls.size() > 1000 || !Set.of("FIELD", "METRIC").contains(input.path("unitSystem").asText())
            || result.path("packageFiles").size() != 1 || !result.path("scheduleMetadata").path("groups").isEmpty()) fail();
        var groups = new HashSet<String>();
        var controlFields = Set.of("group", "sourceFile", "lineNumber", "controlMode", "injectionPhase", "unit", "originalTargetRate", "members");

        for (var g : controls) {
            String phase = g.path("injectionPhase").asText();
            String unit = "FIELD".equals(input.path("unitSystem").asText()) ? phase.equals("GAS") ? "Mscf/day" : "STB/day" : "sm3/day";
            if (!g.isObject() || g.size() != controlFields.size()) fail();
            g.properties().forEach(f -> { if (!controlFields.contains(f.getKey())) fail(); });
            if (!g.path("group").isTextual() || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}", g.path("group").asText())
                || "FIELD".equals(g.path("group").asText()) || !groups.add(g.path("group").asText())
                || !validSource(g.path("sourceFile").asText(), g.path("lineNumber")) || !g.path("sourceFile").asText().equals(input.path("caseName").asText())
                || !g.path("sourceFile").asText().equals(result.path("packageFiles").get(0).path("relativePath").asText())
                || !Set.of("GAS").contains(phase) || !"RATE".equals(g.path("controlMode").asText()) || !unit.equals(g.path("unit").asText())
                || !g.path("originalTargetRate").isNumber() || !Double.isFinite(g.path("originalTargetRate").asDouble()) || g.path("originalTargetRate").asDouble() <= 0 || g.path("originalTargetRate").asDouble() > 100_000_000
                || !g.path("members").isArray() || g.path("members").size() < 2 || g.path("members").size() > 1000) fail();
            var members = new HashSet<String>();
            for (var m : g.path("members")) {
                if (!m.isObject() || m.size() != 2 || !m.has("well") || !m.has("bhpLimit") || !m.path("well").isTextual() || !members.add(m.path("well").asText())
                    || !m.path("bhpLimit").isNumber() || !Double.isFinite(m.path("bhpLimit").asDouble()) || m.path("bhpLimit").asDouble() <= 0 || m.path("bhpLimit").asDouble() > 100_000_000) fail();
                int found = 0;
                for (var w : result.path("scheduleMetadata").path("wells")) if (m.path("well").asText().equals(w.path("name").asText()) && g.path("group").asText().equals(w.path("group").asText())) found++;
                if (found != 1) fail();
            }
            int direct = 0;
            for (var w : result.path("scheduleMetadata").path("wells")) if (g.path("group").asText().equals(w.path("group").asText())) direct++;
            if (direct != members.size()) fail();
            for (var e : result.path("scheduleTimeline")) if (g.path("lineNumber").asInt() >= e.path("lineNumber").asInt()) fail();
        }
        result.put("schemaVersion", "eclipse-data-inspection/8"); result.set("gasInjectionGroupControls", controls); return result;
    }

    private static JsonNode validateV1Fields(ObjectNode input, ObjectMapper mapper, String schemaVersion) {
        String caseName = text(input, "caseName");
        if (!validCaseName(caseName)) fail();
        ArrayNode sections = orderedStrings(input.get("sections"), SECTION_ORDER, true);
        JsonNode unit = input.get("unitSystem");
        if (!(unit == null || unit.isNull() || unit.isTextual() && UNIT_SYSTEMS.contains(unit.asText()))) fail();
        ArrayNode phases = orderedStrings(input.get("phases"), PHASE_ORDER, true);
        JsonNode dimensions = input.get("dimensions");
        if (!(dimensions == null || dimensions.isNull() || validDimensions(dimensions))) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", schemaVersion);
        result.put("caseName", caseName);
        result.set("sections", sections);
        if (unit == null || unit.isNull()) result.putNull("unitSystem"); else result.put("unitSystem", unit.asText());
        result.set("phases", phases);
        if (dimensions == null || dimensions.isNull()) result.putNull("dimensions"); else result.set("dimensions", dimensions);
        return result;
    }

    private static ArrayNode orderedStrings(JsonNode value, List<String> order, boolean allowEmpty) {
        if (value == null || !value.isArray() || (!allowEmpty && value.isEmpty())) fail();
        ArrayNode result = MAPPER.createArrayNode();
        int previous = -1;
        for (JsonNode item : value) {
            if (!item.isTextual()) fail();
            int current = order.indexOf(item.asText());
            if (current < 0 || current <= previous) fail();
            result.add(item.asText()); previous = current;
        }
        return result;
    }

    private static ArrayNode wellNames(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_WELL_NAMES) fail();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (!item.isTextual() || !validWellName(item.asText())) fail();
            result.add(item.asText());
        }
        return result;
    }

    private static ArrayNode scheduleTimeline(JsonNode value, ObjectMapper mapper) {
        return scheduleTimeline(value, mapper, false);
    }

    private static ArrayNode scheduleTimeline(JsonNode value, ObjectMapper mapper, boolean withSource) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_EVENTS) fail();
        ArrayNode result = mapper.createArrayNode();
        int totalDateRecords = 0;
        int totalTstepSteps = 0;
        for (JsonNode event : value) {
            ObjectNode validatedEvent = scheduleEvent(event, mapper, withSource);
            if ("DATES".equals(validatedEvent.get("kind").asText())) {
                totalDateRecords += validatedEvent.get("records").size();
                if (totalDateRecords > MAX_DATE_RECORDS) fail();
            } else {
                totalTstepSteps += validatedEvent.get("steps").size();
                if (totalTstepSteps > MAX_TSTEP_STEPS) fail();
            }
            result.add(validatedEvent);
        }
        return result;
    }

    private static ArrayNode packageFiles(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 4096) fail();
        Set<String> paths = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode file : value) {
            if (file == null || !file.isObject() || file.size() != PACKAGE_FILE_FIELDS.size()) fail();
            file.properties().forEach(field -> { if (!PACKAGE_FILE_FIELDS.contains(field.getKey())) fail(); });
            String relativePath = text((ObjectNode) file, "relativePath");
            JsonNode size = file.get("sizeBytes");
            String sha256 = text((ObjectNode) file, "sha256");
            if (!validRelativePath(relativePath) || !paths.add(relativePath)
                    || size == null || !size.isIntegralNumber() || !size.canConvertToLong()
                    || size.longValue() < 0 || size.longValue() > 64L * 1024 * 1024
                    || sha256 == null || !SHA256.matcher(sha256).matches()) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("relativePath", relativePath);
            normalized.put("sizeBytes", size.longValue());
            normalized.put("sha256", sha256);
            result.add(normalized);
        }
        return result;
    }

    private static ObjectNode scheduleMetadata(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isObject() || value.size() != SCHEDULE_METADATA_FIELDS.size()) fail();
        ObjectNode input = (ObjectNode) value;
        input.properties().forEach(field -> { if (!SCHEDULE_METADATA_FIELDS.contains(field.getKey())) fail(); });
        ArrayNode wells = scheduleWells(input.get("wells"), mapper);
        ArrayNode groups = scheduleGroups(input.get("groups"), mapper);
        ArrayNode records = scheduleRecords(input.get("records"), mapper);
        ArrayNode completions = scheduleCompletions(input.get("completions"), mapper);
        ObjectNode result = mapper.createObjectNode();
        result.set("wells", wells);
        result.set("groups", groups);
        result.set("records", records);
        result.set("completions", completions);
        return result;
    }

    private static ArrayNode scheduleWells(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_METADATA_WELLS) fail();
        Set<String> names = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_WELL_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_WELL_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String name = text(input, "name");
            JsonNode group = input.get("group");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (!validWellName(name) || !names.add(name) || group == null || !(group.isNull() || group.isTextual() && validWellName(group.asText()))
                    || !validSource(sourceFile, lineNumber)) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("name", name);
            if (group.isNull()) normalized.putNull("group"); else normalized.put("group", group.asText());
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ArrayNode scheduleGroups(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_METADATA_GROUPS) fail();
        Set<String> names = new HashSet<>();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_GROUP_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_GROUP_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String name = text(input, "name");
            JsonNode parent = input.get("parent");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (!validWellName(name) || !names.add(name) || parent == null || !(parent.isNull() || parent.isTextual() && validWellName(parent.asText()))
                    || !validSource(sourceFile, lineNumber)) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("name", name);
            if (parent.isNull()) normalized.putNull("parent"); else normalized.put("parent", parent.asText());
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ArrayNode scheduleRecords(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_METADATA_RECORDS) fail();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_RECORD_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_RECORD_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String keyword = text(input, "keyword");
            JsonNode values = input.get("values");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (keyword == null || !SCHEDULE_RECORD_KEYWORDS.contains(keyword) || values == null || !values.isArray()
                    || values.size() == 0 || values.size() > MAX_SCHEDULE_METADATA_VALUES || !validSource(sourceFile, lineNumber)) fail();
            ArrayNode normalizedValues = mapper.createArrayNode();
            for (JsonNode itemValue : values) {
                if (!itemValue.isTextual() || !validMetadataValue(itemValue.asText())) fail();
                normalizedValues.add(itemValue.asText());
            }
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("keyword", keyword);
            normalized.set("values", normalizedValues);
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ArrayNode scheduleCompletions(JsonNode value, ObjectMapper mapper) {
        if (value == null || !value.isArray() || value.size() > MAX_SCHEDULE_COMPLETIONS) fail();
        ArrayNode result = mapper.createArrayNode();
        for (JsonNode item : value) {
            if (item == null || !item.isObject() || item.size() != SCHEDULE_COMPLETION_FIELDS.size()) fail();
            item.properties().forEach(field -> { if (!SCHEDULE_COMPLETION_FIELDS.contains(field.getKey())) fail(); });
            ObjectNode input = (ObjectNode) item;
            String keyword = text(input, "keyword");
            String well = text(input, "well");
            String i = text(input, "i");
            String j = text(input, "j");
            String k1 = text(input, "k1");
            String k2 = text(input, "k2");
            String status = text(input, "status");
            String sourceFile = text(input, "sourceFile");
            JsonNode lineNumber = input.get("lineNumber");
            if (keyword == null || !(keyword.equals("COMPDAT") || keyword.equals("COMPDATM")) || !validWellName(well)
                    || !validMetadataValue(i) || !validMetadataValue(j) || !validMetadataValue(k1)
                    || !validMetadataValue(k2) || !validMetadataValue(status) || !validSource(sourceFile, lineNumber)) fail();
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("keyword", keyword);
            normalized.put("well", well);
            normalized.put("i", i);
            normalized.put("j", j);
            normalized.put("k1", k1);
            normalized.put("k2", k2);
            normalized.put("status", status);
            normalized.put("sourceFile", sourceFile);
            normalized.put("lineNumber", lineNumber.intValue());
            result.add(normalized);
        }
        return result;
    }

    private static ObjectNode scheduleEvent(JsonNode value, ObjectMapper mapper) {
        return scheduleEvent(value, mapper, false);
    }

    private static ObjectNode scheduleEvent(JsonNode value, ObjectMapper mapper, boolean withSource) {
        if (!value.isObject()) fail();
        ObjectNode input = (ObjectNode) value;
        String kind = text(input, "kind");
        if ("DATES".equals(kind)) {
            Set<String> fields = withSource ? DATES_EVENT_FIELDS_V4 : DATES_EVENT_FIELDS;
            if (input.size() != fields.size()) fail();
            input.properties().forEach(field -> { if (!fields.contains(field.getKey())) fail(); });
            JsonNode records = input.get("records");
            if (records == null || !records.isArray() || records.size() > MAX_DATE_RECORDS) fail();
            ArrayNode outputRecords = mapper.createArrayNode();
            for (JsonNode record : records) outputRecords.add(dateRecord(record, mapper));
            ObjectNode result = mapper.createObjectNode();
            result.put("kind", "DATES");
            result.set("records", outputRecords);
            if (withSource) addSource(result, input);
            return result;
        }
        if ("TSTEP".equals(kind)) {
            Set<String> fields = withSource ? TSTEP_EVENT_FIELDS_V4 : TSTEP_EVENT_FIELDS;
            if (input.size() != fields.size()) fail();
            input.properties().forEach(field -> { if (!fields.contains(field.getKey())) fail(); });
            JsonNode steps = input.get("steps");
            if (steps == null || !steps.isArray() || steps.size() > MAX_TSTEP_STEPS) fail();
            ArrayNode outputSteps = mapper.createArrayNode();
            for (JsonNode step : steps) {
                if (!step.isTextual() || !validTstep(step.asText())) fail();
                outputSteps.add(step.asText());
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("kind", "TSTEP");
            result.set("steps", outputSteps);
            if (withSource) addSource(result, input);
            return result;
        }
        fail();
        return null;
    }

    private static void addSource(ObjectNode result, ObjectNode input) {
        String sourceFile = text(input, "sourceFile");
        JsonNode lineNumber = input.get("lineNumber");
        if (!validSource(sourceFile, lineNumber)) fail();
        result.put("sourceFile", sourceFile);
        result.put("lineNumber", lineNumber.intValue());
    }

    private static ObjectNode dateRecord(JsonNode value, ObjectMapper mapper) {
        if (!value.isObject() || value.size() != DATE_FIELDS.size()) fail();
        ObjectNode input = (ObjectNode) value;
        input.properties().forEach(field -> { if (!DATE_FIELDS.contains(field.getKey())) fail(); });
        String day = text(input, "day");
        String month = text(input, "month");
        String year = text(input, "year");
        JsonNode time = input.get("time");
        if (day == null || !DAY.matcher(day).matches() || month == null || !MONTHS.contains(month)
                || year == null || !YEAR.matcher(year).matches()
                || !(time != null && (time.isNull() || time.isTextual() && TIME.matcher(time.asText()).matches()))) fail();
        ObjectNode result = mapper.createObjectNode();
        result.put("day", day);
        result.put("month", month);
        result.put("year", year);
        if (time.isNull()) result.putNull("time"); else result.put("time", time.asText());
        return result;
    }

    private static boolean validDimensions(JsonNode value) {
        if (!value.isObject() || value.size() != 3) return false;
        for (String field : List.of("nx", "ny", "nz")) {
            JsonNode dimension = value.get(field);
            if (dimension == null || !dimension.isInt() || dimension.intValue() <= 0 || dimension.intValue() > MAX_DIMENSION) return false;
        }
        return true;
    }

    private static boolean validTstep(String value) {
        if (!DECIMAL.matcher(value).matches()) return false;
        double parsed = Double.parseDouble(value);
        return Double.isFinite(parsed) && parsed >= 0;
    }

    private static boolean validCaseName(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_CASE_NAME_LENGTH
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0;
    }

    private static boolean validRelativePath(String value) {
        return value != null && !value.isEmpty() && value.length() <= 512
                && value.charAt(0) != '/' && value.charAt(value.length() - 1) != '/'
                && !value.contains("\\") && !value.contains(":") && !value.contains("..")
                && !value.contains("//") && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validWellName(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_WELL_NAME_SCALARS
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.contains("://") || value.contains("..")) return false;
        for (int index = 0; index < value.length();) {
            char character = value.charAt(index);
            if (Character.isSurrogate(character) && (index + 1 >= value.length() || !Character.isSurrogatePair(character, value.charAt(index + 1)))) return false;
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) return false;
            index += Character.charCount(codePoint);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return !(normalized.contains("net.pipe") || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("secret") || normalized.contains("token") || normalized.contains("credential")
                || normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("bearer")
                || normalized.contains("license") || normalized.contains("api_key") || normalized.contains("apikey")
                || normalized.contains("server") || normalized.contains("environment"));
    }

    private static boolean validMetadataValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.contains("://") || value.contains("..") || value.chars().anyMatch(Character::isISOControl)) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return !(normalized.contains("net.pipe") || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("secret") || normalized.contains("token") || normalized.contains("credential")
                || normalized.contains("cookie") || normalized.contains("authorization") || normalized.contains("bearer")
                || normalized.contains("license") || normalized.contains("api_key") || normalized.contains("apikey"));
    }

    private static boolean validSource(String sourceFile, JsonNode lineNumber) {
        return validRelativePath(sourceFile) && lineNumber != null && lineNumber.isIntegralNumber()
                && lineNumber.canConvertToInt() && lineNumber.intValue() > 0 && lineNumber.intValue() <= 2_000_000;
    }

    private static String text(ObjectNode value, String field) {
        JsonNode node = value.get(field); return node != null && node.isTextual() ? node.asText() : null;
    }
    private static void fail() { throw new IllegalArgumentException("Invalid ECLIPSE inspection response"); }
}
