package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineFrictionTests {
    @Test void colebrookMatchesIndependentHighPrecisionRoots() {
        // Independently bisected the original Colebrook equation in lambda with Decimal(70).
        // These are constructed verification cases, not numerical examples supplied by the teacher.
        double[][] references={
                {4000,0,.039907014055634897921519402051849},
                {10000,.0001,.031037212200998626392971652496468},
                {100000,.0002,.019005435221959568884228243078536},
                {1000000,.0003,.015663411942303090979869525074937},
                {100000000,.05,.071550904091083255241242697499482},
                {1e12,.00001,.008063262312672552320012390107588}
        };
        for(double[] row:references)
            assertEquals(row[2],PipelineCalculator.friction(row[0],row[1],"colebrook"),row[2]*1e-12);
    }

    @Test void turbulentDomainHasSmallEquationResidualAndMonotoneRoughnessResponse() {
        double[] reynolds={4000,4000.001,10000,1e5,1e6,1e8,1e12,1e30,1e100,1e300,Double.MAX_VALUE};
        double[] roughness={0,Double.MIN_VALUE,1e-16,1e-8,1e-5,.0003,.01,.05,Math.nextDown(.1)};
        for(double re:reynolds) {
            double previous=0;
            for(double rough:roughness) {
                double f=PipelineCalculator.friction(re,rough,"colebrook");
                String state="Re="+re+", e/D="+rough;
                assertTrue(Double.isFinite(f) && f>0 && f<.12,state);
                // Original equation in lambda, not the transformed Newton iteration.
                double residual=1/Math.sqrt(f)+2*Math.log10(rough/3.7+2.51/(re*Math.sqrt(f)));
                assertEquals(0,residual,2e-11,state);
                assertTrue(f>=previous-1e-14,state);
                previous=f;
            }
        }
    }

    @Test void frictionDecreasesWithReynoldsAndApproachesFullyRoughLimit() {
        for(double rough:new double[]{0,1e-8,.0003,.01,.09}) {
            double previous=1;
            for(double re:new double[]{4000,1e4,1e5,1e6,1e8,1e12,1e100}) {
                double f=PipelineCalculator.friction(re,rough,"colebrook");
                assertTrue(f<=previous+1e-14,"Friction must not increase with Re at fixed roughness");
                previous=f;
            }
            if(rough>0) {
                // Independent asymptote: at very high Re the viscous term vanishes.
                double fullyRough=1/Math.pow(2*Math.log10(3.7/rough),2);
                assertEquals(fullyRough,previous,fullyRough*1e-12);
            }
        }
    }

    @Test void laminarLawAndExistingTransitionAreContinuousAtBothBoundaries() {
        for(String method:new String[]{"colebrook","haaland"}) {
            for(double rough:new double[]{0,.0003,.09}) {
                assertEquals(64.0/1000,PipelineCalculator.friction(1000,rough,method),1e-15);
                assertEquals(64.0/2300,PipelineCalculator.friction(2300,rough,method),1e-15);
                for(double boundary:new double[]{2300,4000}) {
                    double center=PipelineCalculator.friction(boundary,rough,method);
                    assertEquals(center,PipelineCalculator.friction(boundary-1e-6,rough,method),1e-9);
                    assertEquals(center,PipelineCalculator.friction(boundary+1e-6,rough,method),1e-9);
                }
                double lower=PipelineCalculator.friction(2300,rough,method);
                double upper=PipelineCalculator.friction(4000,rough,method);
                for(double re:new double[]{2400,3000,3900}) {
                    double f=PipelineCalculator.friction(re,rough,method);
                    assertTrue(f>=Math.min(lower,upper) && f<=Math.max(lower,upper));
                }
            }
        }
    }

    @Test void haalandRemainsAnExplicitApproximationAcrossTheDomain() {
        for(double re:new double[]{4000,1e4,1e6,1e12,Double.MAX_VALUE}) {
            for(double rough:new double[]{0,.0001,.01,Math.nextDown(.1)}) {
                double f=PipelineCalculator.friction(re,rough,"haaland");
                assertTrue(Double.isFinite(f) && f>0 && f<.12);
                if(re<=1e8) {
                    double exact=PipelineCalculator.friction(re,rough,"colebrook");
                    assertEquals(exact,f,exact*.025);
                }
            }
        }
        assertNotEquals(PipelineCalculator.friction(1e5,.0002,"colebrook"),
                PipelineCalculator.friction(1e5,.0002,"haaland"));
    }

    @Test void invalidMethodOrInputsAreRejectedEvenInTheLaminarBranch() {
        for(String method:new String[]{null,"","Colebrook","unknown"})
            assertThrows(BusinessException.class,()->PipelineCalculator.friction(1000,.001,method));
        for(String method:new String[]{"colebrook","haaland"}) {
            for(double re:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.MIN_VALUE})
                assertThrows(BusinessException.class,()->PipelineCalculator.friction(re,.001,method));
            for(double rough:new double[]{-1e-9,.1,1,Double.NaN,Double.POSITIVE_INFINITY})
                assertThrows(BusinessException.class,()->PipelineCalculator.friction(1000,rough,method));
        }
    }
}
