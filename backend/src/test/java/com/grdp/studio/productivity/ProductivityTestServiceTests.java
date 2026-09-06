package com.grdp.studio.productivity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductivityTestServiceTests {
    private final ProductivityTestService service = new ProductivityTestService(null);

    @Test
    void importsChineseHeaderCsvAndSkipsInvalidRows() {
        String csv = "测点序号,测试气产量,地层/恢复压力,测试流压\n"
                + "1,45,35.61,34.933472\n"
                + "无效,行,会,忽略\n"
                + "2,59.1,35.605,34.655762\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "modified.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        var rows = service.importRows(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().testDailyGasProduction()).isEqualTo(45);
        assertThat(rows.get(1).reservoirPressure()).isEqualTo(35.605);
    }
}
