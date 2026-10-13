package com.grdp.studio.softwareintegration;

import com.grdp.studio.softwareintegration.support.EclipseHistoryCsv;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EclipseHistoryCsvTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private static final String HEADER="well,keyword,unit,timeDays,value\n";
    private tools.jackson.databind.JsonNode summary()throws Exception{return mapper.readTree("""
        {"series":[{"keyword":"WOPR","objectName":"P1","unit":"STB/DAY","points":[{"timeDays":0,"value":0},{"timeDays":5,"value":12},{"timeDays":10,"value":17}]}]}
        """);}
    @Test void CsvPreservesSourceLinesQuotedCellsUtf8BomAndScientificValues(){
        var rows=EclipseHistoryCsv.parse("\uFEFFwell,keyword,unit,timeDays,value\r\n\"P1\",WOPR,\"STB/DAY\",5,1D1\r\nP1,WOPR,STB/DAY,10,2e1\r\n");
        assertEquals(2,rows.size());assertEquals(2,rows.get(0).sourceLine());assertEquals(10,rows.get(0).value());assertEquals(20,rows.get(1).value());
    }
    @Test void DuplicateCanonicalTimesMalformedCsvUnknownColumnsAndNonfiniteValuesReject(){
        for(var csv:new String[]{HEADER+"P1,WOPR,STB/DAY,5,10\nP1,WOPR,BAR,5.0,20",HEADER+"P1,WOPR,STB/DAY,5,NaN",HEADER+"P1,WOPR,STB/DAY,-1,20",HEADER+"\"P1,WOPR,STB/DAY,5,10",HEADER+"\"P1\"x,WOPR,STB/DAY,5,10",HEADER+"P1,WOPR,STB/DAY,5,10,extra","well,keyword,unit,date,value\nP1,WOPR,STB/DAY,5,10"})assertThrows(IllegalArgumentException.class,()->EclipseHistoryCsv.parse(csv));
    }
    @Test void MetricsUseSimulatedMinusObservedAndDiscloseCoverageAndZeroDenominator()throws Exception{
        var rows=EclipseHistoryCsv.parse(HEADER+"P1,WOPR,STB/DAY,0,0\nP1,WOPR,STB/DAY,5,10\nP1,WOPR,STB/DAY,10,20\nP1,WOPR,STB/DAY,15,30");
        var result=EclipseHistoryCsv.compare(rows,summary());var metric=result.metrics().getFirst();
        assertEquals(4,metric.observedCount());assertEquals(3,metric.matchedCount());assertEquals(1,metric.missingCount());assertEquals(.75,metric.coverage());
        assertEquals(5.0/3,metric.mae(),1e-12);assertEquals(Math.sqrt(13.0/3),metric.rmse(),1e-12);assertEquals(-1.0/3,metric.bias(),1e-12);assertEquals(17.5,metric.mape(),1e-12);assertEquals(2,metric.mapeCount());
        assertNull(result.points().getLast().simulated());assertNull(result.points().getLast().difference());assertEquals("没有同一报告时刻",result.points().getLast().reason());
    }
    @Test void UnknownCurveAndWrongUnitsNeverReceiveComparisonValues()throws Exception{
        var wrongUnit=EclipseHistoryCsv.parse(HEADER+"P1,WOPR,SM3/DAY,5,10");var unknown=EclipseHistoryCsv.parse(HEADER+"P2,WOPR,STB/DAY,5,10");
        for(var rows:java.util.List.of(wrongUnit,unknown)){assertThrows(IllegalArgumentException.class,()->EclipseHistoryCsv.requireBaselineCoverage(rows,summary()));var result=EclipseHistoryCsv.compare(rows,summary());assertNull(result.points().getFirst().difference());assertEquals(0,result.metrics().getFirst().matchedCount());assertNull(result.metrics().getFirst().rmse());}
    }
    @Test void ExtremePercentageErrorsStayUnavailableAndNeverSerializeInfinity()throws Exception{
        var rows=EclipseHistoryCsv.parse(HEADER+"P1,WOPR,STB/DAY,5,1e-308");var metric=EclipseHistoryCsv.compare(rows,summary()).metrics().getFirst();
        assertNull(metric.mape());assertEquals(1,metric.mapeOverflowCount());assertEquals(12.0,metric.mae(),1e-12);assertFalse(mapper.writeValueAsString(metric).contains("Infinity"));
    }
    @Test void UnknownUnitWellStateDoesNotBlockActualGasInjectionHistory()throws Exception{
        var nativeSummary=mapper.readTree(getClass().getResourceAsStream("/eclipse-pvdg-summary.json"));
        var rows=EclipseHistoryCsv.parse(HEADER+"I,WGIT,MSCF,6000,600000");EclipseHistoryCsv.requireBaselineCoverage(rows,nativeSummary);
        assertEquals(0.0,EclipseHistoryCsv.compare(rows,nativeSummary).points().getFirst().difference(),1e-12);
        var state=EclipseHistoryCsv.parse(HEADER+"I,WSTAT,1,6000,1");assertThrows(IllegalArgumentException.class,()->EclipseHistoryCsv.requireBaselineCoverage(state,nativeSummary));
    }
    @Test void ActualCommercialSummaryBindsCorrectCurveUnitsAndReportClock()throws Exception{
        var nativeSummary=mapper.readTree(getClass().getResourceAsStream("/eclipse-relperm-summary.json"));
        var rows=EclipseHistoryCsv.parse(HEADER+"P1,WOPR,STB/DAY,100,785.87451171875\nP1,WBHP,PSIA,100,3000");
        EclipseHistoryCsv.requireBaselineCoverage(rows,nativeSummary);var result=EclipseHistoryCsv.compare(rows,nativeSummary);
        assertEquals(2,result.points().size());assertTrue(result.points().stream().allMatch(p->p.difference()==0));
    }
}
