package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.grdp.studio.pipeline.PipelineTemperatureCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineTemperatureTests {
    final PipelineTemperatureCalculator calc=new PipelineTemperatureCalculator();
    static Config config(String edge) {return new Config(edge,"surface-fixed",15.0,1.2,1.5,null,50.0,.1,.012,2300.0,.035,null,6.0,45.0,"测试物性",List.of(new Layer("钢管",6.0,45.0),new Layer("保温层",40.0,.035)));}
    Config edit(Config c,String method,double h,Double alpha,double density,double q,List<Layer> layers) {return new Config(c.edgeId(),method,c.ambientC(),h,c.soilConductivityWmK(),alpha,density,q,c.viscosityMpaS(),c.cpJkgK(),c.gasConductivityWmK(),null,6.0,45.0,"测试物性",layers);}
    static Config innerOnly(String edge) {return new Config(edge,null,null,null,null,null,50.0,.1,.012,2300.0,.035,null,null,null,null,null);}
    static Config wallOnly(String edge) {return new Config(edge,null,null,null,null,null,null,null,null,null,null,null,null,null,null,config(edge).layers());}
    static Config outerOnly(String edge) {return new Config(edge,"surface-fixed",null,1.2,1.5,null,null,null,null,null,null,null,null,null,null,List.of(new Layer(null,6.0,null),new Layer(null,40.0,null)));}
    static Config withAmbient(Config c,Double ambient) {return new Config(c.edgeId(),c.externalMethod(),ambient,c.burialDepthM(),c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),c.densityKgM3(),c.actualFlowM3s(),c.viscosityMpaS(),c.cpJkgK(),c.gasConductivityWmK(),c.pvtId(),c.propertyPressureMpa(),c.propertyTemperatureC(),c.propertySource(),c.layers());}
    static Config withConductivity(Config c,Double conductivity) {return new Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),c.soilConductivityWmK(),c.surfaceCoefficientWm2K(),c.densityKgM3(),c.actualFlowM3s(),c.viscosityMpaS(),c.cpJkgK(),conductivity,c.pvtId(),c.propertyPressureMpa(),c.propertyTemperatureC(),c.propertySource(),c.layers(),c.innerDiameterMm());}
    @Test void explicitConductivityControlsInnerAndOverallButNotMaterialOrExternalCoefficients() {
        var original=config("pipe");var doubled=withConductivity(original,original.gasConductivityWmK()*2);
        var first=calc.calculate("inner",100,original);var second=calc.calculate("inner",100,doubled);
        assertEquals(first.reynolds(),second.reynolds());assertEquals(first.prandtl()/2,second.prandtl(),1e-12);
        assertEquals(first.alphaInside()*Math.pow(2,.57),second.alphaInside(),1e-9);
        assertTrue(calc.calculate("overall",100,doubled).innerAreaU()>calc.calculate("overall",100,original).innerAreaU());
        for(String kind:List.of("wall","outer"))assertEquals(calc.calculate(kind,100,original),calc.calculate(kind,100,doubled));
        assertEquals(doubled.gasConductivityWmK(),calc.withMass(doubled,5).gasConductivityWmK());
    }
    @Test void missingOrInvalidConductivityHasNoImplicitDefaultForFluidHeatTransfer() {
        for(Double conductivity:java.util.Arrays.asList(null,0.0,-.01,Double.NaN,Double.POSITIVE_INFINITY)) {
            var config=withConductivity(config("pipe"),conductivity);
            for(String kind:List.of("inner","overall"))
                assertTrue(assertThrows(BusinessException.class,()->calc.calculate(kind,100,config)).getMessage().contains("气体导热系数 λ"));
            for(String kind:List.of("wall","outer"))assertNotNull(calc.calculate(kind,100,config));
        }
    }
    @Test void innerCalculationNeedsOnlyFlowGeometryAndGasProperties() {
        var r=calc.calculate("inner",100,innerOnly("a"));
        assertEquals(4*.1*50/(Math.PI*.1*.000012),r.reynolds(),1e-8);
        assertEquals(calc.calculate(100,config("a")).alphaInside(),r.alphaInside(),1e-10);
        assertNull(r.alphaOutside());assertNull(r.wallConductance());assertNull(r.documentK());assertNull(r.innerAreaU());assertTrue(r.layers().isEmpty());
        assertTrue(r.notes().stream().noneMatch(note->note.contains("资料2")||note.contains("资料3")));
    }
    @Test void independentInnerDiameterDoesNotChangeOtherCoefficientsOrCoupledGeometry() {
        var c=config("a");
        var trial=new Config(c.edgeId(),c.externalMethod(),c.ambientC(),c.burialDepthM(),c.soilConductivityWmK(),
                c.surfaceCoefficientWm2K(),c.densityKgM3(),c.actualFlowM3s(),c.viscosityMpaS(),c.cpJkgK(),
                c.gasConductivityWmK(),c.pvtId(),c.propertyPressureMpa(),c.propertyTemperatureC(),c.propertySource(),c.layers(),75.0);
        assertEquals(calc.calculate("inner",75,c),calc.calculate("inner",100,trial));
        assertNotEquals(calc.calculate("inner",100,c).alphaInside(),calc.calculate("inner",100,trial).alphaInside());
        for(String kind:List.of("wall","outer","overall"))
            assertEquals(calc.calculate(kind,100,c),calc.calculate(kind,100,trial),kind+" must use topology diameter");
        assertEquals(calc.calculate(100,calc.withMass(c,5)),calc.calculate(100,calc.withMass(trial,5)));
    }
    @Test void wallCalculationNeedsOnlyGeometryAndMaterialParameters() {
        var r=calc.calculate("wall",100,wallOnly("a"));
        assertEquals(1/(.006/45+.04/.035),r.wallConductance(),1e-12);
        assertEquals(calc.calculate(100,config("a")).wallResistance(),r.wallResistance(),1e-12);
        assertEquals(2,r.layers().size());assertNull(r.reynolds());assertNull(r.alphaOutside());assertNull(r.documentK());assertNull(r.innerAreaU());
        assertTrue(r.notes().stream().noneMatch(note->note.contains("资料1")||note.contains("资料3")));
    }
    @Test void outerCalculationNeedsOnlyLayerThicknessAndExternalBoundary() {
        var c=outerOnly("a");var r=calc.calculate("outer",100,c);
        assertEquals(.192,r.outerDiameterM(),1e-12);
        assertEquals(calc.calculate(100,config("a")).alphaOutside(),r.alphaOutside(),1e-12);
        var unrelatedInvalid=new Config("a",c.externalMethod(),Double.NaN,c.burialDepthM(),c.soilConductivityWmK(),-1.0,-1.0,-1.0,-1.0,-1.0,-1.0,99L,-1.0,Double.NaN,"",
                List.of(new Layer("",6.0,-1.0),new Layer(null,40.0,null)));
        assertEquals(r,calc.calculate("outer",100,unrelatedInvalid));
        assertNull(r.alphaInside());assertNull(r.wallConductance());assertNull(r.documentK());assertNull(r.innerAreaU());assertTrue(r.layers().isEmpty());
    }
    @Test void materialNameThicknessAndConductivitySufficeForWallAndOverallCalculations() {
        var c=edit(config("a"),"surface-fixed",1.2,null,50,.1,List.of(new Layer("钢管",5.0,50.0)));
        var wall=calc.calculate("wall",100,c);
        assertEquals(10000.0,wall.wallConductance(),1e-9);
        assertEquals(.110,wall.outerDiameterM(),1e-12);
        assertEquals(.1*Math.log(1.1)/100,wall.wallResistance(),1e-12);
        var overall=calc.calculate("overall",100,c);
        assertEquals(wall.wallConductance(),overall.wallConductance());
        assertTrue(Double.isFinite(overall.documentK())&&overall.documentK()>0);
        assertTrue(Double.isFinite(overall.innerAreaU())&&overall.innerAreaU()>0);
    }
    @Test void overallCombinesIndependentResultsWithoutRequiringAmbientTemperature() {
        var c=withAmbient(config("a"),null);
        var inside=calc.calculate("inner",100,c);var wall=calc.calculate("wall",100,c);var outside=calc.calculate("outer",100,c);var overall=calc.calculate("overall",100,c);
        assertEquals(inside.alphaInside(),overall.alphaInside());assertEquals(wall.wallConductance(),overall.wallConductance());assertEquals(outside.alphaOutside(),overall.alphaOutside());
        assertEquals(1/(inside.innerResistance()+wall.wallResistance()+outside.outerResistance()),overall.innerAreaU(),1e-12);
        assertEquals(1/(1/inside.alphaInside()+1/wall.wallConductance()+1/outside.alphaOutside()),overall.documentK(),1e-12);
        assertEquals(calc.calculate(100,config("a")),overall);
    }
    @Test void overallValidationIdentifiesWhichComponentNeedsInputs() {
        assertEquals("管内壁放热系数：请填写有效的气体密度",assertThrows(BusinessException.class,()->calc.calculate("overall",100,wallOnly("a"))).getMessage());
        assertTrue(assertThrows(BusinessException.class,()->calc.calculate("overall",100,innerOnly("a"))).getMessage().startsWith("管道导热系数："));
        var c=config("a");
        var noBurial=new Config("a",c.externalMethod(),null,null,c.soilConductivityWmK(),null,c.densityKgM3(),c.actualFlowM3s(),c.viscosityMpaS(),c.cpJkgK(),c.gasConductivityWmK(),null,null,null,null,c.layers());
        assertEquals("外部放热系数：请填写有效的管中心埋深",assertThrows(BusinessException.class,()->calc.calculate("overall",100,noBurial)).getMessage());
    }
    @Test void unknownCalculationKindIsRejected() {
        assertEquals("未知的温度计算类型",assertThrows(BusinessException.class,()->calc.calculate("inside",100,config("a"))).getMessage());
        assertThrows(BusinessException.class,()->calc.calculate(null,100,config("a")));
    }
    @Test void radialResistanceAndAreaConversionAreConsistent() {
        var r=calc.calculate(100,config("a"));
        assertEquals(.192,r.outerDiameterM(),1e-12);
        double expectedWall=.1/(2*45)*Math.log(.112/.1)+.1/(2*.035)*Math.log(.192/.112);
        assertEquals(expectedWall,r.wallResistance(),1e-12);
        assertEquals(1/(r.innerResistance()+r.wallResistance()+r.outerResistance()),r.innerAreaU(),1e-12);
        assertEquals(.1/(.192*r.alphaOutside()),r.outerResistance(),1e-12);
        assertNotEquals(r.documentK(),r.innerAreaU(),1e-3);
        var bare=calc.calculate(100,edit(config("a"),"surface-fixed",1.2,null,50,.1,List.of(config("a").layers().getFirst())));
        assertTrue(bare.innerAreaU()>r.innerAreaU());
    }
    @Test void internalCoefficientConservesMassFlow() {
        var c=config("a");var a=calc.calculate(100,c);var b=calc.calculate(100,edit(c,"surface-fixed",1.2,null,100,.05,c.layers()));
        assertEquals(a.alphaInside(),b.alphaInside(),1e-10);
    }
    @Test void secondBoundaryApproachesFirstBoundaryAsSurfaceResistanceVanishes() {
        var c=config("a");var first=calc.calculate(100,edit(c,"surface-fixed",.15,null,50,.1,c.layers()));
        var second=calc.calculate(100,edit(c,"surface-resistance",.15,1e12,50,.1,c.layers()));
        assertEquals(first.alphaOutside(),second.alphaOutside(),1e-8);
        var finite=calc.calculate(100,edit(c,"surface-resistance",.15,10.0,50,.1,c.layers()));
        assertTrue(finite.alphaOutside()<first.alphaOutside());
    }
    @Test void secondBoundaryIncludesDepthDiameterRatioTwoAndRejectsOnlyLargerRatios() {
        var c=config("a");
        var layers=List.of(new Layer("钢管",50.0,45.0));
        // D外=0.2 m, h=0.4 m: the teacher's flowchart assigns equality to the shallow branch.
        var boundary=edit(c,"surface-resistance",.4,10.0,50,.1,layers);
        var result=calc.calculate("outer",100,boundary);
        assertEquals(.2,result.outerDiameterM(),1e-12);
        assertEquals(6.120611787987425,result.alphaOutside(),1e-12);
        assertEquals(result.alphaOutside(),calc.calculate("overall",100,boundary).alphaOutside());
        var below=calc.calculate("outer",100,edit(c,"surface-resistance",.4-1e-9,10.0,50,.1,layers));
        assertEquals(below.alphaOutside(),result.alphaOutside(),1e-8);
        var error=assertThrows(BusinessException.class,()->calc.calculate("outer",100,
                edit(c,"surface-resistance",.4+1e-9,10.0,50,.1,layers)));
        assertTrue(error.getMessage().contains("h/D外 > 2"));
        // The first boundary remains available for deep burial when that boundary is appropriate.
        assertTrue(calc.calculate("outer",100,edit(c,"surface-fixed",1.2,null,50,.1,layers)).alphaOutside()>0);
    }
    @Test void incompleteLayersInvalidBurialAndUnsupportedFlowAreRejected() {
        var c=config("a");
        assertThrows(BusinessException.class,()->calc.calculate(100,edit(c,"surface-fixed",.01,null,50,.1,c.layers())));
        assertThrows(BusinessException.class,()->calc.calculate(100,edit(c,"surface-fixed",1.2,null,50,.0000001,c.layers())));
        assertThrows(BusinessException.class,()->calc.calculate(100,edit(c,"surface-resistance",1.2,10.0,50,.1,c.layers())));
        assertThrows(BusinessException.class,()->calc.calculate(100,edit(c,"surface-fixed",1.2,null,50,.1,List.of(new Layer("钢管",6.0,null)))));
    }
}
