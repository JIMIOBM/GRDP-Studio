package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.grdp.studio.pipeline.PipelineGasProperties.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineGasPropertiesTests {
    private final PipelineGasProperties calc = new PipelineGasProperties();
    private static final List<String> METHODS = List.of("PR", "SRK", "BWRS");
    private static final List<String> HEAVY_N_ALKANES = List.of("NC7", "NC8", "NC9", "NC10");
    private static final List<Fraction> METHANE = List.of(new Fraction("CH4", 1));
    private static final List<Fraction> MIXTURE = List.of(new Fraction("CH4", .88), new Fraction("C2H6", .06),
            new Fraction("C3H8", .02), new Fraction("IC4", .005), new Fraction("NC4", .005),
            new Fraction("IC5", .002), new Fraction("NC5", .002), new Fraction("NC6", .001),
            new Fraction("N2", .01), new Fraction("CO2", .01), new Fraction("H2S", .005));

    private double parameter(ParameterSnapshot snapshot, String group, String key) {
        return snapshot.groups().stream().filter(g -> g.key().equals(group)).findFirst().orElseThrow()
                .rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow().value();
    }

    @Test void parameterPreviewExposesConstantsBeforeOperatingPointWithoutSolvingAnEos() {
        var fractions = List.of(new Fraction("N2", .95), new Fraction("CO2", .05));
        var snapshot = calc.parameters("PR", fractions, null, null);
        assertEquals("", snapshot.issue());
        assertEquals(R, parameter(snapshot, "constants", "R"));
        assertEquals(.45724, parameter(snapshot, "methodConstants", "omegaA"));
        assertEquals(.07780, parameter(snapshot, "methodConstants", "omegaB"));
        assertEquals(.95 * 126.192 + .05 * 304.1282, parameter(snapshot, "mixture", "criticalTemperatureK"), 1e-12);
        assertEquals(.95 * 3395800 + .05 * 7377300, parameter(snapshot, "mixture", "criticalPressurePa"), 1e-8);
        assertEquals(.95 * .02801348 + .05 * .0440098, parameter(snapshot, "mixture", "molarMassKgMol"), 1e-14);
        assertFalse(snapshot.groups().stream().anyMatch(g -> g.key().equals("condition") || g.key().equals("caloric")));
        assertTrue(snapshot.species().stream().allMatch(s -> s.usedMoleFraction() != null));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.groups().getFirst().rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.species().getFirst().groups().clear());
        var rounded = calc.parameters("SRK", List.of(new Fraction("N2", .9500001), new Fraction("CO2", .05)), null, null);
        assertEquals(.9500001, rounded.species().getFirst().moleFraction());
        assertEquals(.9500001 / 1.0000001, rounded.species().getFirst().usedMoleFraction(), 1e-15);
    }

    @Test void incompleteCompositionPreviewShowsKnownDataWithoutInventingMixtureOrGasState() {
        var snapshot = calc.parameters("BWRS", List.of(new Fraction("CO2", .012), new Fraction("N2", .008)), 5.0, 40.0);
        assertFalse(snapshot.issue().isBlank());
        assertEquals(2, snapshot.species().size());
        assertTrue(snapshot.species().stream().allMatch(s -> s.usedMoleFraction() == null));
        assertTrue(snapshot.species().stream().allMatch(s -> s.groups().stream().anyMatch(g -> g.key().equals("bwrsPure"))));
        assertFalse(snapshot.groups().stream().anyMatch(g -> g.key().equals("mixture") || g.key().equals("eosCoefficients") || g.key().equals("caloric")));
        assertEquals(.443690, parameter(snapshot, "methodConstants", "A1"));
        assertEquals(0, parameter(snapshot, "methodConstants", "binaryInteraction"));
        assertEquals(5e6, parameter(snapshot, "condition", "pressurePa"));
        assertEquals(313.15, parameter(snapshot, "condition", "temperatureK"));
        var invalidPoint = calc.parameters("PR", METHANE, -1.0, -273.15);
        assertFalse(invalidPoint.issue().isBlank());
        assertFalse(invalidPoint.groups().stream().anyMatch(g -> g.key().equals("condition") || g.key().equals("caloric")));
    }

    @Test void parameterSnapshotReconstructsActualCpAndBwrsPressureWithDisplayedUnits() {
        for (String method : METHODS) {
            var prepared = calc.prepare(method, MIXTURE);
            var state = prepared.at(8, 40);
            var snapshot = prepared.parameters(8.0, 40.0, state);
            double molarCp = parameter(snapshot, "caloric", "idealCpJmolK") - parameter(snapshot, "constants", "R")
                    + parameter(snapshot, "caloric", "residualCvJmolK") + parameter(snapshot, "caloric", "cpMinusCvJmolK");
            assertEquals(state.cpJkgK(), molarCp / parameter(snapshot, "mixture", "molarMassKgMol"), 1e-8, method);
            double rho = parameter(snapshot, "caloric", "molarDensityMolM3");
            assertEquals(state.densityKgM3(), rho * state.molarMassKgMol(), 1e-12);
            assertEquals(1 / rho, parameter(snapshot, "caloric", "molarVolumeM3Mol"), 1e-15);
            if (method.equals("BWRS")) {
                double t = parameter(snapshot, "condition", "temperatureK"), r = parameter(snapshot, "constants", "R");
                double a0=parameter(snapshot,"eosCoefficients","A0"), b0=parameter(snapshot,"eosCoefficients","B0"),
                        c0=parameter(snapshot,"eosCoefficients","C0"), d0=parameter(snapshot,"eosCoefficients","D0"), e0=parameter(snapshot,"eosCoefficients","E0"),
                        a=parameter(snapshot,"eosCoefficients","a"), b=parameter(snapshot,"eosCoefficients","b"), c=parameter(snapshot,"eosCoefficients","c"),
                        d=parameter(snapshot,"eosCoefficients","d"), alpha=parameter(snapshot,"eosCoefficients","alpha"), gamma=parameter(snapshot,"eosCoefficients","gamma");
                double pressure = rho*r*t + (b0*r*t-a0-c0/(t*t)+d0/Math.pow(t,3)-e0/Math.pow(t,4))*rho*rho
                        + (b*r*t-a-d/t)*Math.pow(rho,3) + alpha*(a+d/t)*Math.pow(rho,6)
                        + c*Math.pow(rho,3)/(t*t)*(1+gamma*rho*rho)*Math.exp(-gamma*rho*rho);
                assertEquals(8e6, pressure, .01, "Displayed mixed BWRS coefficients must reproduce the pressure in Pa");
            }
        }
    }

    @Test void parameterPreviewIncludesNitrogenAndHydrogenSulfideIdealGasPowerTerms() {
        var snapshot = calc.parameters("PR", List.of(new Fraction("N2", .5), new Fraction("H2S", .5)), null, 26.85);
        for (var species : snapshot.species()) {
            var rows = species.groups().stream().filter(g -> g.key().equals("idealGas")).findFirst().orElseThrow().rows();
            assertTrue(rows.stream().anyMatch(r -> r.key().equals("powerN1")));
            assertTrue(rows.stream().anyMatch(r -> r.key().equals("powerT1")));
            double cp = rows.stream().filter(r -> r.key().equals("idealCpJmolK")).findFirst().orElseThrow().value();
            // CoolProp's fluid EOS files use R=8.31451 for nitrogen and 8.314472 for H2S.
            // Compare the independent CP0MOLAR reference on this model's common-R basis.
            double reference = species.code().equals("N2") ? 29.12615123704937 : 34.129691483108616;
            double referenceR = species.code().equals("N2") ? 8.31451 : 8.314472;
            assertEquals(reference / referenceR * R, cp, 1e-8);
            assertTrue(species.groups().stream().filter(g -> g.key().equals("physical")).findFirst().orElseThrow().rows()
                    .stream().anyMatch(r -> r.key().equals("criticalMolarDensityMolM3") && r.unit().equals("mol/m³")));
        }
    }

    @Test void allThreeMethodsMatchIndependentHighPrecisionMethaneReference() {
        // 45-digit mpmath solve of the original P(rho,T), with numerical integration of
        // uR = integral[(P-T*P_T)/rho^2]drho and numerical differentiation of h at constant P.
        double[] z = {.8784164495102625098, .90955728371044517622, .89793384496997508124};
        double[] cp = {2781.1566966207624421, 2790.763324857859484, 2764.6948965677473105};
        for (int i = 0; i < METHODS.size(); i++) {
            State state = calc.calculate(METHODS.get(i), METHANE, 8, 40);
            assertEquals(z[i], state.z(), 1e-9, METHODS.get(i));
            assertEquals(cp[i], state.cpJkgK(), 1e-5, METHODS.get(i));
        }
    }

    @Test void idealGasLimitRetainsFullIdealHeatCapacityForAllMethods() {
        for (String method : METHODS) {
            Prepared prepared = calc.prepare(method, METHANE);
            State state = prepared.at(.000001, 26.85);
            assertEquals(1, state.z(), 5e-7, method);
            assertEquals(prepared.idealCpMolar(300) / state.molarMassKgMol(), state.cpJkgK(), .003, method);
            assertTrue(state.cpJkgK() > 2200 && state.cpJkgK() < 2250, method);
        }
    }

    @Test void cpMatchesIndependentConstantPressureEnthalpyDerivative() {
        for (String method : METHODS) for (var fractions : List.of(METHANE, MIXTURE)) {
            Prepared prepared = calc.prepare(method, fractions);
            for (double pressure : List.of(.101325, 1.0, 8.0, 20.0)) for (double temperature : List.of(10.0, 40.0, 100.0)) {
                State state = prepared.at(pressure, temperature);
                double dt = .005;
                double derivative = (prepared.enthalpyJkg(pressure, temperature + dt)
                        - prepared.enthalpyJkg(pressure, temperature - dt)) / (2 * dt);
                assertEquals(derivative, state.cpJkgK(), Math.abs(derivative) * 2e-6,
                        method + ", P=" + pressure + ", T=" + temperature);
            }
        }
    }

    @Test void solvedDensitySatisfiesTheSelectedEquationAndUnits() {
        for (String method : METHODS) {
            Prepared p = calc.prepare(method, MIXTURE);
            for (double pressure : List.of(.000001, .1, 5.0, 30.0, 50.0)) {
                State state = p.at(pressure, 40);
                double rhoMolar = state.densityKgM3() / state.molarMassKgMol();
                assertEquals(pressure * 1e6, p.eosPressurePa(rhoMolar, 313.15), Math.max(.001, pressure * 1e-3), method);
                assertEquals(pressure * 1e6 * state.molarMassKgMol() / (state.z() * R * 313.15), state.densityKgM3(), 1e-10);
            }
        }
    }

    @Test void idealHeatCapacityHasVerifiedComponentReferencesAt300K() {
        // Values independently evaluated with CoolProp's ideal-gas heat-capacity implementation; J/(mol K).
        double[] cpReferences = {35.77751627968782, 52.69762366969565, 73.69807439485697, 97.14148533445152, 98.94859752934657, 119.52284982537357, 120.70291343806693, 143.47573981379188,
                165.98056336753982, 189.94900855894613, 211.45927145708688, 234.1901301595204,
                29.12615123704937, 37.22551376752397, 34.129691483108616};
        assertEquals(cpReferences.length, calc.catalog().size());
        for (int i = 0; i < calc.catalog().size(); i++) {
            String code = calc.catalog().get(i).code();
            double cp = calc.prepare("PR", List.of(new Fraction(code, 1))).idealCpMolar(300);
            assertEquals(cpReferences[i], cp, cpReferences[i] * 2e-5, code);
        }
    }

    @Test void c7ThroughC10CatalogUsesIdentifiedPureSpeciesAndVerifiedCriticalConstants() {
        assertEquals(List.of("CH4", "C2H6", "C3H8", "IC4", "NC4", "IC5", "NC5", "NC6",
                "NC7", "NC8", "NC9", "NC10", "N2", "CO2", "H2S"), calc.catalog().stream().map(ComponentInfo::code).toList());
        String[] names = {"正庚烷", "正辛烷", "正壬烷", "正癸烷"};
        // CoolProp v7.2.0 (98b3523d...), each fluid's STATES.critical and EOS[0].
        double[][] constants = {
                {.100202, 540.13, 2736000.0, .349, 2315.3230474441625},
                {.114229, 568.74, 2483591.199677694, .39752829818330415, 2031},
                {.1282551, 594.5500000000001, 2281000.0, .4433, 1810},
                {.14228168, 617.7, 2103000.0, .4884, 1640.0000000000002}
        };
        for (int i = 0; i < HEAVY_N_ALKANES.size(); i++) {
            String code = HEAVY_N_ALKANES.get(i);
            var component = calc.catalog().stream().filter(value -> value.code().equals(code)).findFirst().orElseThrow();
            assertEquals(names[i], component.name());
            assertEquals(constants[i][0], component.molarMassKgMol(), 1e-15);
            assertEquals(constants[i][1], component.criticalTemperatureK(), 1e-10);
            assertEquals(constants[i][2], component.criticalPressurePa(), 1e-7);
            assertEquals(constants[i][3], component.acentricFactor(), 1e-14);
            var preview = calc.parameters("BWRS", List.of(new Fraction(code, 1)), null, 26.85);
            var physical = preview.species().getFirst().groups().stream().filter(g -> g.key().equals("physical")).findFirst().orElseThrow();
            double criticalDensity = physical.rows().stream().filter(r -> r.key().equals("criticalMolarDensityMolM3")).findFirst().orElseThrow().value();
            assertEquals(constants[i][4], criticalDensity, 1e-10);
        }
        for (String unsupported : List.of("C7", "C10", "C6+", "C7+", "C10+", "IC7", "NC11")) {
            assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of(new Fraction(unsupported, 1))),
                    "Unspecified cuts and unsupported isomers cannot silently become pure n-alkanes: " + unsupported);
        }
    }

    @Test void c7ThroughC10IdealCpMatchesIndependentCoolProp720AcrossTemperature() {
        // CP0MOLAR from the separately installed official CoolProp 7.2.0 binary, not this Java formula.
        // Use the common R basis because each source fluid EOS retains its publication's gas constant.
        double[] temperatures = {300, 400, 500, 650};
        double[] sourceR = {8.31451, 8.3144598, 8.314472, 8.314472};
        double[][] reference = {
                {165.98056336753982, 210.65827494506144, 252.09975342908456, 302.92477711770744},
                {189.94900855894613, 239.69008431037315, 286.5822462117334, 344.99428554331445},
                {211.45927145708688, 268.77093740993445, 321.4761124163469, 385.61107258893054},
                {234.1901301595204, 297.98317033273935, 356.4330087785295, 427.0425913785646}
        };
        for (int i = 0; i < HEAVY_N_ALKANES.size(); i++) {
            var prepared = calc.prepare("PR", List.of(new Fraction(HEAVY_N_ALKANES.get(i), 1)));
            for (int t = 0; t < temperatures.length; t++) {
                assertEquals(reference[i][t] / sourceR[i] * R, prepared.idealCpMolar(temperatures[t]), 1e-8,
                        HEAVY_N_ALKANES.get(i) + " T=" + temperatures[t]);
            }
        }
    }

    @Test void heptaneAlyLeeTransformationPreservesTheOriginalHyperbolicHeatCapacity() {
        var prepared = calc.prepare("PR", List.of(new Fraction("NC7", 1)));
        for (double t : List.of(200.0, 300.0, 400.0, 500.0, 650.0)) {
            // Original n-Heptane JSON: A + B(C/T/sinh(C/T))² + D(E/T/cosh(E/T))²,
            // plus a second sinh contribution. Evaluate directly, independently of PE expansion.
            double x = 169.789 / t, y = 836.195 / t, z = 1760.46 / t;
            double reference = R * (4 + 13.7266 * Math.pow(x / Math.sinh(x), 2)
                    + 30.4707 * Math.pow(y / Math.cosh(y), 2) + 43.5561 * Math.pow(z / Math.sinh(z), 2));
            assertEquals(reference, prepared.idealCpMolar(t), 1e-9);
        }
    }

    @Test void allMethodsMatchIndependentHighPrecisionC7ThroughC10References() {
        // 40-digit mpmath at 1 MPa absolute and 650 K, above all four pure-species critical temperatures.
        // Solve original P(rho,T); integrate uR=integral[(P-T*P_T)/rho²]drho; differentiate h at fixed P.
        // These check numerical implementation, not an experimentally validated operating range.
        double[][] z = {
                {.92700790794227480029, .93777049983708650871, .93283148749722131218},
                {.90257345107473075866, .91511619602247367460, .90959019618635853061},
                {.87372292416006647198, .88803456970411860991, .88140197502610934901},
                {.83919664910200074606, .85531483534168913958, .84811808081234517969}
        };
        double[][] cp = {
                {3072.7558300777543080, 3073.3961944698956435, 3079.2612389710998115},
                {3080.8730349545374057, 3081.7093998972549003, 3091.5522559734531832},
                {3080.8691581564954371, 3081.9405327314978698, 3096.9245934989198366},
                {3093.9304199940361565, 3095.2989308702871757, 3115.7004158280032076}
        };
        double[][] density = {
                {20.00070983834200977, 19.77116595992611132, 19.87584728121340187},
                {23.41781002006660726, 23.09684136101210122, 23.23716075111861093},
                {27.16147629994560825, 26.72373948820469540, 26.92483698665393800},
                {31.37167460432759245, 30.78048353288844074, 31.04167308808402858}
        };
        for (int i = 0; i < HEAVY_N_ALKANES.size(); i++) for (int method = 0; method < METHODS.size(); method++) {
            var fractions = List.of(new Fraction(HEAVY_N_ALKANES.get(i), 1));
            var prepared = calc.prepare(METHODS.get(method), fractions);
            var state = prepared.at(1, 376.85);
            String context = HEAVY_N_ALKANES.get(i) + " " + METHODS.get(method);
            assertEquals(z[i][method], state.z(), 1e-9, context);
            assertEquals(cp[i][method], state.cpJkgK(), 1e-5, context);
            assertEquals(density[i][method], state.densityKgM3(), 1e-7, context);
            double derivative = (prepared.enthalpyJkg(1, 376.855) - prepared.enthalpyJkg(1, 376.845)) / .01;
            assertEquals(state.cpJkgK(), derivative, state.cpJkgK() * 2e-6, context);
        }
    }

    @Test void fifteenComponentGasMixtureRetainsPressureAndCaloricConsistency() {
        var fractions = new java.util.ArrayList<Fraction>(MIXTURE);
        fractions.set(0, new Fraction("CH4", .879));
        HEAVY_N_ALKANES.forEach(code -> fractions.add(new Fraction(code, .00025)));
        for (String method : METHODS) {
            var prepared = calc.prepare(method, fractions);
            for (double pressure : List.of(1.0, 8.0, 20.0)) for (double temperature : List.of(40.0, 100.0)) {
                var state = prepared.at(pressure, temperature);
                double rho = state.densityKgM3() / state.molarMassKgMol();
                assertEquals(pressure * 1e6, prepared.eosPressurePa(rho, temperature + 273.15), .01, method);
                double derivative = (prepared.enthalpyJkg(pressure, temperature + .005)
                        - prepared.enthalpyJkg(pressure, temperature - .005)) / .01;
                assertEquals(state.cpJkgK(), derivative, state.cpJkgK() * 2e-6, method);
                assertNotEquals(calc.calculate(method, MIXTURE, pressure, temperature).molarMassKgMol(), state.molarMassKgMol());
            }
        }
        for (String method : METHODS) for (String code : HEAVY_N_ALKANES) {
            assertThrows(BusinessException.class, () -> calc.calculate(method, List.of(new Fraction(code, 1)), 5, 26.85),
                    "Adding a component must not bypass gas-phase validation: " + code + " " + method);
        }
    }

    @Test void physicalPropertiesRespondToMethodCompositionAndOperatingPoint() {
        State pr = calc.calculate("PR", MIXTURE, 8, 30);
        State srk = calc.calculate("SRK", MIXTURE, 8, 30);
        State bwrs = calc.calculate("BWRS", MIXTURE, 8, 30);
        assertNotEquals(pr.z(), srk.z()); assertNotEquals(pr.z(), bwrs.z());
        assertNotEquals(srk.cpJkgK(), bwrs.cpJkgK());
        assertNotEquals(pr.z(), calc.calculate("PR", MIXTURE, 3, 30).z());
        assertNotEquals(pr.cpJkgK(), calc.calculate("PR", MIXTURE, 8, 80).cpJkgK());
        assertNotEquals(pr.molarMassKgMol(), calc.calculate("PR", METHANE, 8, 30).molarMassKgMol());
    }

    @Test void componentAndStateValidationRejectsMissingUnsupportedOrMalformedInputs() {
        assertThrows(BusinessException.class, () -> calc.prepare("PR", null));
        assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of()));
        assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of(new Fraction("C7+", 1))));
        assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of(new Fraction("CH4", 100))));
        assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of(new Fraction("CH4", .9))));
        assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of(new Fraction("CH4", .5), new Fraction("CH4", .5))));
        assertThrows(BusinessException.class, () -> calc.prepare("PR", List.of(new Fraction("CH4", Double.NaN))));
        assertThrows(BusinessException.class, () -> calc.prepare("fixed", METHANE));
        for (String method : METHODS) {
            for (double p : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
                assertThrows(BusinessException.class, () -> calc.calculate(method, METHANE, p, 30));
            for (double t : new double[]{-273.15, -274, Double.NaN, Double.POSITIVE_INFINITY})
                assertThrows(BusinessException.class, () -> calc.calculate(method, METHANE, 1, t));
            assertThrows(BusinessException.class, () -> calc.calculate(method, METHANE, Double.MAX_VALUE, 30),
                    "Overflow during MPa-to-Pa conversion must not produce a reported result");
        }
    }

    @Test void statesOutsideFormerInputBoundsSolveTheSelectedEquationAndRetainCaloricConsistency() {
        var nitrogen=List.of(new Fraction("N2",1));
        for(String method:METHODS) {
            var eos=calc.prepare(method,nitrogen);
            // High pressure, a stable gas below 200 K, and temperature above 500 K.
            // These are numerical regressions, not experimental validity-range claims.
            for(double[] pt:List.of(new double[]{70,40},new double[]{.1,-123.15},new double[]{1,326.85})) {
                var state=eos.at(pt[0],pt[1]);
                double rho=state.densityKgM3()/state.molarMassKgMol();
                assertEquals(pt[0]*1e6,eos.eosPressurePa(rho,pt[1]+273.15),pt[0]*1e-2,method);
                assertTrue(state.z()>0&&Double.isFinite(state.z()));
                double dt=.005;
                double derivative=(eos.enthalpyJkg(pt[0],pt[1]+dt)-eos.enthalpyJkg(pt[0],pt[1]-dt))/(2*dt);
                assertEquals(derivative,state.cpJkgK(),Math.abs(derivative)*2e-6,method);
            }
        }
    }

    @Test void subPascalStatesPreserveTheIdealGasLimitWithoutAbsoluteDensityToleranceOrSquareUnderflow() {
        for(String method:METHODS) for(var composition:List.of(METHANE,List.of(new Fraction("N2",1)))) {
            var eos=calc.prepare(method,composition);
            // 0.01 Pa previously generated spurious PR roots through discriminant cancellation.
            for(double pressure:List.of(1e-7,1e-8,1e-12,1e-20,1e-200)) {
                var state=eos.at(pressure,26.85);
                double rho=state.densityKgM3()/state.molarMassKgMol();
                assertEquals(1,eos.eosPressurePa(rho,300)/(pressure*1e6),1e-8,method+" pressure="+pressure);
                assertEquals(1,state.z(),5e-9,method);
                assertEquals(eos.idealCpMolar(300)/state.molarMassKgMol(),state.cpJkgK(),1e-5,method);
            }
        }
    }

    @Test void onlyRoundingErrorIsNormalizedAndPreparationIsReused() {
        var fractions = List.of(new Fraction("CH4", .9000001), new Fraction("N2", .1));
        calc.validateComposition(fractions);
        assertSame(calc.prepare("PR", fractions), calc.prepare("PR", fractions));
        assertThrows(BusinessException.class, () -> calc.validateComposition(List.of(new Fraction("CH4", .999))));
    }

    @Test void liquidAndAmbiguousPhaseConditionsAreNotReportedAsGasResults() {
        for (String method : METHODS)
            assertThrows(BusinessException.class, () -> calc.calculate(method, List.of(new Fraction("NC6", 1)), 5, 26.85), method);
        for(String method:List.of("PR","SRK")) {
            for(double pressure:List.of(.1,1e-8)) {
                var error=assertThrows(BusinessException.class,()->calc.calculate(method,METHANE,pressure,-123.15));
                assertTrue(error.getMessage().contains("多个稳定根"),
                        "Removing bounds and resolving small roots must preserve real multiple-root checks");
            }
        }
    }
}
