package com.grdp.studio.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static com.grdp.studio.pipeline.PipelineDtos.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineThermalFlowTests {
    /* Teacher source: 管道热容及压降计算/管道模型/传热/
       智慧管道-水平管道-管输量（传热）.docx, step 6, Suhov outlet temperature.
       The source uses -D_j * (P_in-P_out); this API uses +JT * (P_out-P_in), K/MPa.
       Independent oracles below integrate the energy ODE with RK4, without calling
       the production temperature step or reproducing its exponential weight. */

    @Test void suhovTemperatureMatchesIndependentEnergyIntegrationForLinearPressure() {
        for(double jt:List.of(4.2,-4.2)) {
            double expected=integrateEnergy(343.15,283.15,2.7,jt,s->-5.8,4096);
            double actual=PipelineCalculator.stepTemperature(343.15,283.15,2.7,jt,-5.8);
            assertEquals(expected,actual,2e-10);
            double wallOnly=PipelineCalculator.stepTemperature(343.15,283.15,2.7,0,-5.8);
            assertTrue(jt>0 ? actual<wallOnly : actual>wallOnly,
                    "A positive JT coefficient cools on a pressure drop; a negative one warms");
        }
    }

    @Test void zeroHeatTransferRetainsTheFullAdiabaticJtChange() {
        assertEquals(320.15-4.0*6.25,
                PipelineCalculator.stepTemperature(320.15,290.15,0,4.0,-6.25),1e-12);
        assertEquals(320.15+4.0*6.25,
                PipelineCalculator.stepTemperature(320.15,290.15,0,-4.0,-6.25),1e-12);
        assertEquals(320.15,
                PipelineCalculator.stepTemperature(320.15,290.15,0,4.0,0),1e-12);
    }

    @Test void arbitrarilySmallHeatTransferIsContinuousAndNumericallyStable() {
        double inlet=330.15,ambient=280.15,jt=3.0,dp=-4.0;
        double adiabatic=inlet+jt*dp;
        for(double exponent:List.of(Double.MIN_VALUE,1e-20,1e-16,1e-12,1e-8,1e-6)) {
            double actual=PipelineCalculator.stepTemperature(inlet,ambient,exponent,jt,dp);
            // First-order perturbation of dT/ds=-x(T-Ta)+JT*dP/ds about x=0.
            double expected=adiabatic-exponent*(inlet-ambient+jt*dp/2);
            assertTrue(Double.isFinite(actual));
            assertEquals(expected,actual,Math.max(2e-12,30*exponent*exponent));
        }
    }

    @Test void noJtTermReducesToTheClassicalWallHeatLossLaw() {
        for(double exponent:List.of(.01,1.0,10.0)) {
            double expected=280.15+60*Math.exp(-exponent);
            assertEquals(expected,
                    PipelineCalculator.stepTemperature(340.15,280.15,exponent,0,-10),1e-12);
        }
    }

    @Test void arbitrarySubdivisionsPreserveTheLinearPressureEnergySolution() {
        double[] boundaries={0,.01,.07,.31,.32,.76,1};
        double actual=343.15;
        for(int i=1;i<boundaries.length;i++) {
            double fraction=boundaries[i]-boundaries[i-1];
            actual=PipelineCalculator.stepTemperature(actual,283.15,2.7*fraction,4.2,-5.8*fraction);
        }
        double expected=integrateEnergy(343.15,283.15,2.7,4.2,s->-5.8,4096);
        assertEquals(expected,actual,2e-10);
    }

    @Test void piecewiseLinearPressureConvergesToANonlinearPressureEnergyBalance() {
        // P(s)=P_in-5.8*s^2, so dP/ds=-11.6*s; the production step assumes
        // linear pressure inside each cell, and must converge as cells are refined.
        double expected=integrateEnergy(343.15,283.15,2.7,4.2,s->-11.6*s,8192);
        double previousError=Double.POSITIVE_INFINITY;
        for(int cells:List.of(4,8,16,32,64)) {
            double actual=343.15;
            for(int cell=0;cell<cells;cell++) {
                double start=(double)cell/cells,end=(double)(cell+1)/cells;
                double dp=-5.8*(end*end-start*start);
                actual=PipelineCalculator.stepTemperature(actual,283.15,2.7/cells,4.2,dp);
            }
            double error=Math.abs(actual-expected);
            assertTrue(error<previousError*.27,
                    "Doubling cells must show second-order convergence for smooth pressure");
            previousError=error;
        }
        assertTrue(previousError<.003);
    }

    @Test void flowCalculationUsesTheSameEnergyBalanceAlongItsPressureTrajectory() {
        var source=PipelineCalculatorTests.example();
        var pipe=new Segment("热积分校核管段",4000,100,.03,0,15,8);
        var input=new Input("outlet","heat","colebrook",6.0,null,8.0,55.0,
                source.gasGravity(),source.z(),source.viscosityMpaS(),source.cpJkgK(),5.0,
                source.standardPressurePa(),source.standardTemperatureK(),source.standardZ(),
                List.of(pipe),List.of(),source.constraints());
        var result=new PipelineCalculator().calculate(input);
        double gasConstant=287.05/input.gasGravity();
        double standardDensity=input.standardPressurePa()
                /(input.standardZ()*gasConstant*input.standardTemperatureK());
        double massRate=input.rate10k()*10000/86400*standardDensity;
        double heatPerMetre=pipe.heatTransferWm2K()*Math.PI*(pipe.diameterMm()/1000)
                /(massRate*input.cpJkgK());
        double integrated=input.inletC()+273.15;
        // Hold the computed pressure path fixed to isolate energy integration from
        // hydraulic discretization. Integrate each cell independently with RK4.
        for(int i=1;i<result.points().size();i++) {
            var before=result.points().get(i-1);var after=result.points().get(i);
            double length=after.distanceM()-before.distanceM();
            double pressureChangeMpa=after.pressureMpa()-before.pressureMpa();
            integrated=integrateEnergy(integrated,pipe.ambientC()+273.15,
                    heatPerMetre*length,input.jtKmpa(),s->pressureChangeMpa,32);
            assertEquals(integrated-273.15,after.temperatureC(),2e-9);
        }
        assertTrue(result.pressureDropMpa()>.01,"The regression needs a material JT contribution");
        assertEquals(integrated-273.15,result.outletC(),2e-9);
    }

    private static double integrateEnergy(double initial,double ambient,double exponent,
            double jt,DoubleUnaryOperator pressureDerivative,int steps) {
        double temperature=initial,h=1.0/steps;
        for(int i=0;i<steps;i++) {
            double s=i*h;
            double k1=-exponent*(temperature-ambient)+jt*pressureDerivative.applyAsDouble(s);
            double k2=-exponent*(temperature+h*k1/2-ambient)+jt*pressureDerivative.applyAsDouble(s+h/2);
            double k3=-exponent*(temperature+h*k2/2-ambient)+jt*pressureDerivative.applyAsDouble(s+h/2);
            double k4=-exponent*(temperature+h*k3-ambient)+jt*pressureDerivative.applyAsDouble(s+h);
            temperature+=h*(k1+2*k2+2*k3+k4)/6;
        }
        return temperature;
    }
}
