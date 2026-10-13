package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.execution.PipesimVfpTablesResultValidator;
import com.grdp.studio.softwareintegration.execution.PipesimWellResultValidator;
import com.grdp.studio.softwareintegration.execution.SoftwareIntegrationRunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipesimVfpTablesResultValidatorTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipesimVfpTablesResultValidator validator = new PipesimVfpTablesResultValidator();

    @Test
    void acceptsOfficialVfpprodRowsWithThpIndexesAndLiquidRateValues() {
        var result = objectMapper.readTree(validResultJson());

        var validated = validator.validate(result);

        assertThat(validated.terminalStatus()).isEqualTo(SoftwareIntegrationRunStatus.SUCCEEDED);
        assertThat(validated.contract()).isEqualTo("VALID_FULL");
        assertThat(validated.result()).isSameAs(result);
    }

    @Test
    void rejectsVfpprodRowsWhoseValuesFollowThpInsteadOfLiquidRateAxis() {
        var result = objectMapper.readTree(validResultJson().replace("\"values\":[100,200,300]", "\"values\":[100,200]"));

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    @Test
    void rejectsIncompleteVfpprodIndexGrid() {
        var result = objectMapper.readTree(validResultJson().replace(
                "{\"thpIndex\":2,\"waterCutIndex\":1,\"gorIndex\":1,\"artificialLiftIndex\":1,\"values\":[700,800,900]},", ""));

        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(PipesimWellResultValidator.ResultValidationException.class);
    }

    private static String validResultJson() {
        return """
                {
                  "schemaVersion":"pipesim-vfp-tables-result/2", "model_kind":"black_oil_liquid",
                  "runTask":"vfp-tables", "resultContract":"VALID_FULL", "producer":"Well",
                  "reservoirSimulator":"ECLIPSE", "tableNumber":2, "includeTemperature":true,
                  "bottomHoleDatumDepth":30,
                  "axes":{"liquidRatesStbPerDay":[100,200,300],"outletPressuresPsi":[250,350],"waterCutFraction":[0.4],"gorMscfPerStb":[0.265],"artificialLiftInjectionDpPsi":[40,50]},
                  "table":{"valueName":"BHP","unit":"psia","rows":[
                    {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[100,200,300]},
                    {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[400,500,600]},
                    {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[700,800,900]},
                    {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[1000,1100,1200]}]},
                  "temperatureTable":{"valueName":"TEMP","unit":"F","rows":[
                    {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[50,51,52]},
                    {"thpIndex":1,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[53,54,55]},
                    {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":1,"values":[56,57,58]},
                    {"thpIndex":2,"waterCutIndex":1,"gorIndex":1,"artificialLiftIndex":2,"values":[59,60,61]}]},
                  "vfpTableContent":"VFPPROD 2 30.0 'LIQ' 'WCT' 'GOR' 'THP' ' ' 'FIELD' 'BHP' /",
                  "vfpTableWithTemperatureContent":"VFPPROD 2 30.0 'LIQ' 'WCT' 'GOR' 'THP' ' ' 'FIELD' 'TEMP' /"
                }
                """;
    }
}
