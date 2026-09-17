package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PipelineStandingViscosityTests {
    @Test void matchesIndependentHighPrecisionEvaluationOfTeacherFormula() {
        // mpmath 55-digit direct double sum, independent of the production Horner evaluation.
        assertEquals(.007877035832240076170968894,
                PipelineStandingViscosity.calculate(.1,20,.6,200,4.6,0,0,0).viscosityMpaS(),2e-16);
        assertEquals(.011881281911017809131800975,
                PipelineStandingViscosity.calculate(8,40,.6,200,4.6,0,0,0).viscosityMpaS(),2e-16);
        assertEquals(.012259626749874559337375174,
                PipelineStandingViscosity.calculate(8,40,.7,200,4.6,.02,.03,.01).viscosityMpaS(),2e-16);
        // Pr=10 distinguishes the teacher's a3 from the other published coefficient.
        assertEquals(.046090384123776731907551570,
                PipelineStandingViscosity.calculate(46,40,.6,200,4.6,0,0,0).viscosityMpaS(),2e-15);
    }
    @Test void convertsCelsiusKelvinAndFahrenheitSeparatelyAndKeepsNonHydroCorrections() {
        var r=PipelineStandingViscosity.calculate(8,40,.7,200,4.6,.02,.03,.01);
        assertEquals(1.56575,r.reducedTemperature(),1e-15);
        assertEquals(8/4.6,r.reducedPressure(),1e-15);
        assertEquals(.0098152464,r.lowPressureViscosityMpaS(),1e-17);
        assertEquals(.000165528627586417958489,r.nitrogenCorrectionMpaS(),1e-18);
        assertEquals(.000145004706099883560686,r.carbonDioxideCorrectionMpaS(),1e-18);
        assertEquals(.0000241488235972104049275,r.hydrogenSulfideCorrectionMpaS(),1e-18);
        assertEquals(.010149928557283511924102267,r.correctedLowPressureViscosityMpaS(),1e-17);
        assertEquals(.6372097611509558159685884462,r.pressurePolynomial(),2e-14);
    }
    @Test void preparedCompositionUsesTheSameMixtureConventionForEveryEos() {
        var fractions=List.of(new PipelineGasProperties.Fraction("CH4",.94),new PipelineGasProperties.Fraction("N2",.02),
                new PipelineGasProperties.Fraction("CO2",.03),new PipelineGasProperties.Fraction("H2S",.01));
        var gas=new PipelineGasProperties();
        var reference=gas.prepare("PR",fractions).standingViscosity(8,40);
        for(String method:List.of("PR","SRK","BWRS"))assertEquals(reference,gas.prepare(method,fractions).standingViscosity(8,40));
        double mass=.94*.0160428+.02*.02801348+.03*.0440098+.01*.03408088;
        assertEquals(mass/(PipelineGasProperties.R/287.05),reference.gasGravity(),1e-15);
        assertEquals(.94*190.564+.02*126.192+.03*304.1282+.01*373.1,reference.pseudoCriticalTemperatureK(),1e-12);
        assertEquals(.94*4.5992+.02*3.3958+.03*7.3773+.01*9,reference.pseudoCriticalPressureMpa(),1e-14);
    }
    @Test void rejectsInvalidInputsAndUnrepresentableResultsWithoutReplacingThemWithDefaults() {
        for(double p:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE})
            assertThrows(BusinessException.class,()->PipelineStandingViscosity.calculate(p,40,.6,200,4.6,0,0,0));
        for(double t:new double[]{-273.15,Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE})
            assertThrows(BusinessException.class,()->PipelineStandingViscosity.calculate(8,t,.6,200,4.6,0,0,0));
        assertThrows(BusinessException.class,()->PipelineStandingViscosity.calculate(8,40,.6,200,4.6,.8,.3,0));
        assertThrows(BusinessException.class,()->PipelineStandingViscosity.calculate(8,40,.6,200,4.6,-.1,0,0));
        assertThrows(BusinessException.class,()->PipelineStandingViscosity.calculate(8,40,0,200,4.6,0,0,0));
    }
}
