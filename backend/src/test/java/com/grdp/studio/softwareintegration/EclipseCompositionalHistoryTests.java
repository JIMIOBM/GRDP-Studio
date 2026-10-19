package com.grdp.studio.softwareintegration;
import com.grdp.studio.softwareintegration.support.EclipseHistoryCsv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
class EclipseCompositionalHistoryTests {
 private final JsonMapper mapper=new JsonMapper();
 private JsonNode summary(){return mapper.readTree(getClass().getResourceAsStream("/eclipse-compositional-batch-baseline-result.json")).path("summary");}
 private java.util.List<JsonNode> oils(){var oils=new ArrayList<JsonNode>();for(var s:summary().path("series"))if(s.path("keyword").asText().equals("FOPT"))oils.add(s);assertEquals(2,oils.size());return oils;}
 private String csv(){String s="well,keyword,unit,timeDays,value,vectorIndex\n";for(var oil:oils())s+=",FOPT,STB,5475,2593602.25,"+oil.path("vectorIndex").asInt()+"\n";return s;}
 @Test void duplicateRawOilColumnsRemainIndependentAtOneObservationTime(){var observations=EclipseHistoryCsv.parse(csv());EclipseHistoryCsv.requireBaselineCoverage(observations,summary());var c=EclipseHistoryCsv.compare(observations,summary());assertEquals(2,c.metrics().size());assertNotEquals(c.metrics().get(0).vectorIndex(),c.metrics().get(1).vectorIndex());assertTrue(c.metrics().stream().allMatch(m->m.rmse()==0&&m.matchedCount()==1));assertTrue(mapper.readTree(mapper.writeValueAsString(c)).path("points").get(0).path("vectorIndex").isIntegralNumber());}
 @Test void foreignColumnMetadataAndUnitsCannotResolveToTheFirstMatchingOilCurve(){for(var changed:new String[]{csv().replace("FOPT","FPR"),csv().replace("STB","SM3"),csv().replace(","+oils().get(0).path("vectorIndex").asInt()+"\n",",999999\n")})assertThrows(IllegalArgumentException.class,()->EclipseHistoryCsv.requireBaselineCoverage(EclipseHistoryCsv.parse(changed),summary()));}
 @Test void invalidIndicesDuplicateObservationsAndMixedHeadersAreRejected(){for(var index:new String[]{"-1","1.0","1e0","true","1000000",""}){var text="well,keyword,unit,timeDays,value,vectorIndex\n,FOPT,STB,5475,1,"+index;assertThrows(IllegalArgumentException.class,()->EclipseHistoryCsv.parse(text));}var duplicate=csv()+",FOPT,STB,5475,1,"+oils().get(0).path("vectorIndex").asInt();assertThrows(IllegalArgumentException.class,()->EclipseHistoryCsv.parse(duplicate));}
 @Test void missingTimesStayNullAndIndexIsIncludedInMissingMetrics(){var observations=EclipseHistoryCsv.parse(csv().replace("5475","5474.5"));var c=EclipseHistoryCsv.compare(observations,summary());assertEquals(2,c.metrics().size());assertTrue(c.points().stream().allMatch(p->p.simulated()==null&&p.difference()==null&&p.reason().equals("没有同一报告时刻")&&p.vectorIndex()!=null));assertTrue(c.metrics().stream().allMatch(m->m.rmse()==null&&m.coverage()==0));}
}
