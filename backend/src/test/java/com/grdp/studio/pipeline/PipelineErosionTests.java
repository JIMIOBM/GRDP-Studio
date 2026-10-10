package com.grdp.studio.pipeline;

import com.grdp.studio.wellbore.erosion.model.ErosionCalculator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineDtos.*;
import static com.grdp.studio.pipeline.PipelineErosion.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineErosionTests {
    static PipelineErosionLiquidSource.Snapshot water() {
        return new PipelineErosionLiquidSource.Snapshot(12L,"水样",6L,4L,"测试井",10000.0,10.0,0,0,"test-source",null);
    }
    static Configuration configuration() {
        return new Configuration(12L,water(),List.of(new SegmentInput("e",.005,.01,2650.0)),List.of());
    }
    private static PipelineTopology.Edge edge() {
        return new PipelineTopology.Edge("e","a","b","管道1",Map.of());
    }
    private static Point point(double position,double velocity,double density) {
        return new Point(0,"位置"+position,position,8,40,velocity,density,1e6,.02,.9,2200.0,.011);
    }
    @Test void takesMaximumRatioAtOneActualLocationInsteadOfMixingIndependentVelocityAndDensityExtrema() {
        var points=List.of(point(100,20,200),point(150,25,20));
        var result=screen(edge(),"one",points,100,8,configuration(),(p,t)->1000.0);
        assertEquals(0,result.distanceM());assertEquals("位置100.0",result.pointLabel());
        assertEquals(20,result.actualVelocityMs());assertEquals(200,result.gasDensityKgM3());
        assertEquals(result.actualVelocityMs()/result.criticalVelocityMs(),result.velocityRatio(),1e-12);
        assertEquals(2,result.evaluatedPoints());assertTrue(result.applicable());
        assertFalse(result.velocityDefinitionVerified());assertTrue(result.status().startsWith("reference_"));
        assertTrue(result.reason().contains("完整定义尚未核实"));
    }
    @Test void formulaUsesPercentNumeralWhileDensityUsesItsHundredthAndMatchesWellboreKernel() {
        var result=screen(edge(),"one",List.of(point(0,20,50)),100,8,configuration(),(p,t)->1000.0);
        assertEquals((1-.005/100-.01/100)*50+.005/100*1000+.01/100*2650,result.mixtureDensityKgM3(),1e-12);
        assertEquals(-13.5*Math.log(.01)+7.2138,result.sandFactor(),1e-12);
        var kernel=new ErosionCalculator();
        assertEquals(kernel.calculateCriticalVelocity(kernel.calculateCriticalCoefficient(kernel.calculateLiquidHoldupFactor(.005),
                kernel.calculateSandFactor(.01)),result.mixtureDensityKgM3()),result.criticalVelocityMs(),1e-12);
        assertEquals(ErosionCalculator.VERSION,result.modelVersion());
    }
    @Test void perCaseOverridesInheritNullValuesAndNeverTurnExplicitZeroIntoADefault() {
        var base=configuration();
        var config=new Configuration(base.liquidPvtId(),base.liquidPvt(),base.segments(),
                List.of(new CaseInput("one","e",null,0.0,null),new CaseInput("two","e",.006,null,null)));
        assertEquals(new SegmentInput("e",.005,0.0,2650.0),parameters(config,"e","one"));
        assertEquals(new SegmentInput("e",.006,.01,2650.0),parameters(config,"e","two"));
        assertEquals(base.segments().getFirst(),parameters(config,"e","three"));
        var zero=screen(edge(),"one",List.of(point(0,20,50)),100,8,config,(p,t)->{fail("No log(0) or PVT evaluation");return null;});
        assertEquals("not_applicable",zero.status());assertNull(zero.criticalVelocityMs());assertTrue(zero.reason().contains("无砂"));
    }
    @Test void threeSupplementalInputsCalculateWithoutMaterialAndInvalidOrMissingInputsStayUnevaluated() {
        var complete=screen(edge(),"one",List.of(point(0,20,50)),100,8,configuration(),(p,t)->1000.0);
        assertNotNull(complete.criticalVelocityMs());assertTrue(complete.status().startsWith("reference_"));
        assertTrue(complete.reason().contains("P110 研究公式"));
        var missing=screen(edge(),"one",List.of(point(0,20,50)),100,8,null,null);
        assertEquals("not_evaluated",missing.status());assertNull(missing.criticalVelocityMs());
        var invalid=new Configuration(12L,water(),List.of(new SegmentInput("e",-1.0,.01,2650.0)),List.of());
        assertEquals("not_applicable",screen(edge(),"one",List.of(point(0,20,50)),100,8,invalid,(p,t)->1000.0).status());
    }
    @Test void anyOutOfRangeSampleMakesTheWholePipeAnExtrapolationEvenWhenItsRatioIsNotTheMaximum() {
        var result=screen(edge(),"one",List.of(point(0,20,200),point(50,1,20)),100,8,configuration(),(p,t)->1000.0);
        assertEquals(20,result.actualVelocityMs());assertFalse(result.applicable());assertTrue(result.reason().contains("超出标定范围"));
        assertNotNull(result.criticalVelocityMs());assertTrue(result.status().startsWith("reference_"));
    }
    @Test void oneUnusableLiquidStateCannotBeHiddenBySelectingAnotherValidPoint() {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var result=screen(edge(),"one",List.of(point(0,20,200),point(50,25,20)),100,8,configuration(),(p,t)->{
            if(count.incrementAndGet()==2)throw new IllegalArgumentException("水相物性超出适用范围");return 1000.0;
        });
        assertEquals("not_evaluated",result.status());assertEquals(1,result.evaluatedPoints());assertEquals(2,result.sampledPoints());
        assertNull(result.velocityRatio());assertTrue(result.reason().contains("不能给出全管最不利结果"));
    }
}
