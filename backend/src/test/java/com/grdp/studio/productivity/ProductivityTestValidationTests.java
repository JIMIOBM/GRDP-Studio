package com.grdp.studio.productivity;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.grdp.studio.productivity.ProductivityTestModels.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductivityTestValidationTests {
    private final ProductivityTestService service = new ProductivityTestService(null);

    @Test
    void acceptsInjectionBackPressureBinomialWithPositiveRateConvention() {
        assertDoesNotThrow(() -> service.validate(request("binomial", List.of(
                new InputItem(1, 10d, 30d, 32d),
                new InputItem(2, 20d, 30d, 35d)
        ))));
    }

    @Test
    void acceptsInjectionBackPressureExponentialWithPositiveRateConvention() {
        assertDoesNotThrow(() -> service.validate(request("exponential", List.of(
                new InputItem(1, 10d, 30d, 32d),
                new InputItem(2, 20d, 30d, 35d)
        ))));
    }

    @Test
    void rejectsWrongInjectionPressureDirection() {
        assertThrows(BusinessException.class, () -> service.validate(request("binomial", List.of(
                new InputItem(1, 10d, 30d, 29d),
                new InputItem(2, 20d, 30d, 35d)
        ))));
    }

    @Test
    void rejectsDuplicatePointNumbers() {
        assertThrows(BusinessException.class, () -> service.validate(request("binomial", List.of(
                new InputItem(1, 10d, 30d, 32d),
                new InputItem(1, 20d, 30d, 35d)
        ))));
    }

    @Test
    void rejectsNonPositiveMaximumFormationPressure() {
        SaveRequest valid = request("binomial", List.of(
                new InputItem(1, 10d, 30d, 32d),
                new InputItem(2, 20d, 30d, 35d)
        ));
        SaveRequest invalid = new SaveRequest(valid.testId(), valid.projectId(), valid.gasReservoirId(),
                valid.wellName(), valid.pvtId(), valid.operationType(), valid.testMethod(), valid.testNo(),
                valid.testDate(), valid.wellType(), valid.replaceInput(),
                new Input(0d, 40d, null, null, null, null, null, null, null, null, null, null),
                valid.inputItems(), valid.result());
        assertThrows(BusinessException.class, () -> service.validate(invalid));
    }

    private SaveRequest request(String resultType, List<InputItem> points) {
        boolean exponential = "exponential".equals(resultType);
        List<ChartPoint> chart = List.of(new ChartPoint(
                exponential ? "analysis" : "regularized", 1, 1, 10d, 2d, false, null));
        List<IprPoint> ipr = List.of(new IprPoint(1, 1,
                exponential ? 30d : null, 10d, 32d, false, null));
        Result result = new Result(resultType, "pressure", null,
                exponential ? null : 0.2d, exponential ? null : 0.01d, 50d,
                exponential ? 2d : null, exponential ? 0.8d : null,
                null, null, 0.99d, null, "可靠", chart, ipr);
        Input input = new Input(30d, 40d, null, null,
                null, null, null, null, null, null, null, null);
        return new SaveRequest(null, 6, 1, "A3-1", null,
                "injection", "back-pressure", null, LocalDate.of(2026, 9, 9),
                null, true, input, points, result);
    }
}
