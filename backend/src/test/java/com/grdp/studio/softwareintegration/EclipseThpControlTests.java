package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseThpControlParameters;
import com.grdp.studio.softwareintegration.support.EclipseDataInspectionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EclipseThpControlTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode parameters() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":"eclipse-thp-control-parameters/1","baselineRunId":1,"well":"HORIZ","phase":"INITIAL","sourceFile":"CASE.DATA","lineNumber":30,"unit":"psia","originalThp":500,"targetThp":700,"vfpTableNumber":1,"vfpTableSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","originalAlq":0}
            """);
    }
    private ObjectNode inspection() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":"eclipse-data-inspection/5","caseName":"CASE.DATA","sections":["RUNSPEC","SCHEDULE"],"unitSystem":"FIELD","phases":["OIL","WATER","GAS"],"dimensions":{"nx":1,"ny":1,"nz":1},"wellNames":["HORIZ"],
             "packageFiles":[{"relativePath":"CASE.DATA","sizeBytes":100,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
             "scheduleTimeline":[{"kind":"TSTEP","steps":["1","9"],"sourceFile":"CASE.DATA","lineNumber":40}],
             "scheduleMetadata":{"wells":[{"name":"HORIZ","group":"G","sourceFile":"CASE.DATA","lineNumber":20}],"groups":[],"completions":[],"records":[{"keyword":"WCONPROD","values":["HORIZ","OPEN","THP","5*","400","500","1"],"sourceFile":"CASE.DATA","lineNumber":30}]},
             "vfpTables":[{"number":1,"datumDepth":6900,"flowType":"LIQ","waterFractionType":"WCT","gasFractionType":"GOR","alqType":"","unitSystem":"FIELD","sourceFile":"CASE.DATA","lineNumber":5,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","flowAxis":[1,3000],"thpAxis":[200,1000],"waterFractionAxis":[0,0.8],"gasFractionAxis":[1,4],"alqAxis":[0]}]}
            """);
    }
    @Test void validCompleteInitialTableMatchesDefaultedAlq() throws Exception {
        var p = parameters(); var i = inspection();
        assertThat(EclipseThpControlParameters.valid(p)).isTrue();
        assertThat(EclipseThpControlParameters.matchesInspection(p, i)).isTrue();
        assertThat(EclipseDataInspectionValidator.validateAndSerialize(i, mapper)).contains("eclipse-data-inspection/5");
    }
    @Test void staleTableSourcePressureAlqAndOutOfRangeReject() throws Exception {
        for (String key : new String[] {"targetThp", "originalThp", "originalAlq", "vfpTableNumber", "lineNumber", "vfpTableSha256", "unit"}) {
            var p = parameters();
            switch (key) {
                case "vfpTableSha256" -> p.put(key, "b".repeat(64));
                case "unit" -> p.put(key, "barsa");
                default -> p.put(key, key.equals("targetThp") ? 1500 : 99);
            }
            assertThat(EclipseThpControlParameters.matchesInspection(p, inspection())).as(key).isFalse();
        }
    }
    @Test void malformedAxesAndUnboundTableMetadataReject() throws Exception {
        for (String fault : new String[] {"axis", "hash", "source", "extra"}) {
            var i = inspection(); var t = (ObjectNode) i.path("vfpTables").get(0);
            switch (fault) {
                case "axis" -> t.set("thpAxis", mapper.readTree("[1000,200]"));
                case "hash" -> t.put("sha256", "bad");
                case "source" -> t.put("sourceFile", "OTHER.DATA");
                default -> t.put("extra", true);
            }
            assertThatThrownBy(() -> EclipseDataInspectionValidator.validateAndSerialize(i, mapper)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void malformedScalarAndExtraRequestReject() throws Exception {
        var p = parameters(); p.put("lineNumber", "30"); assertThat(EclipseThpControlParameters.valid(p)).isFalse();
        p = parameters(); p.put("extra", true); assertThat(EclipseThpControlParameters.valid(p)).isFalse();
    }
}
