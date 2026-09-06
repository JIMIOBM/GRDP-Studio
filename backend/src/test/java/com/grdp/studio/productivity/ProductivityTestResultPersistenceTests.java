package com.grdp.studio.productivity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;

import static com.grdp.studio.productivity.ProductivityTestModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProductivityTestResultPersistenceTests {
    private JdbcTemplate jdbc;
    private ProductivityTestService service;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:productivity-results;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("project_well_productivity_exponential_ipr_item",
                "project_well_productivity_exponential_output_item", "project_well_productivity_exponential_output",
                "project_well_productivity_binomial_ipr_item", "project_well_productivity_binomial_output_item",
                "project_well_productivity_binomial_output", "project_well_productivity_test_input_item",
                "project_well_productivity_test_input", "project_well_productivity_test", "project_well_pvt",
                "project_well_heads")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE project_well_heads(id BIGINT AUTO_INCREMENT PRIMARY KEY,project_id BIGINT,project_gas_reservoir_id BIGINT,well_name VARCHAR(64))");
        jdbc.execute("CREATE TABLE project_well_pvt(id BIGINT AUTO_INCREMENT PRIMARY KEY,well_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE project_well_productivity_test(id BIGINT AUTO_INCREMENT PRIMARY KEY,
                project_id BIGINT,project_gas_reservoir_id BIGINT,well_id BIGINT,well_name VARCHAR(64),pvt_id BIGINT,
                operation_type VARCHAR(32),test_method VARCHAR(32),test_no INT,test_name VARCHAR(100),test_date DATE,
                well_type VARCHAR(32),status VARCHAR(32))
                """);
        jdbc.execute("""
                CREATE TABLE project_well_productivity_test_input(id BIGINT AUTO_INCREMENT PRIMARY KEY,test_id BIGINT,
                maximum_formation_pressure DOUBLE,formation_temperature DOUBLE,one_point_alpha DOUBLE,gas_type VARCHAR(32),
                specific_gravity DOUBLE,hydrogen_sulfide DOUBLE,carbon_dioxide DOUBLE,nitrogen DOUBLE,
                condensate_oil_density DOUBLE,modification_method VARCHAR(32),deviation_factor_method VARCHAR(32),viscosity_method VARCHAR(32))
                """);
        jdbc.execute("CREATE TABLE project_well_productivity_test_input_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,input_id BIGINT,test_point_number INT,test_daily_gas_production DOUBLE,reservoir_pressure DOUBLE,test_flow_pressure DOUBLE)");
        jdbc.execute("""
                CREATE TABLE project_well_productivity_binomial_output(id BIGINT AUTO_INCREMENT PRIMARY KEY,test_id BIGINT,
                pressure_method VARCHAR(32),source_evaluation_id BIGINT,darcy_seepage_coefficient DOUBLE,
                non_darcy_seepage_coefficient DOUBLE,open_flow_capacity DOUBLE,gradient DOUBLE,intercept DOUBLE,r_squared DOUBLE,
                reliability_level INT,reliability_description VARCHAR(255),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE project_well_productivity_binomial_output_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,output_id BIGINT,curve_type VARCHAR(32),point_number INT,x_value DOUBLE,y_value DOUBLE,is_deleted BOOLEAN,data_label VARCHAR(255))");
        jdbc.execute("CREATE TABLE project_well_productivity_binomial_ipr_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,output_id BIGINT,curve_number INT,point_number INT,gas_production DOUBLE,bottom_hole_flowing_pressure DOUBLE,is_deleted BOOLEAN,data_label VARCHAR(255))");
        jdbc.execute("""
                CREATE TABLE project_well_productivity_exponential_output(id BIGINT AUTO_INCREMENT PRIMARY KEY,test_id BIGINT,
                pressure_method VARCHAR(32),productivity_coefficient DOUBLE,productivity_exponent DOUBLE,open_flow_capacity DOUBLE,
                r_squared DOUBLE,reliability_description VARCHAR(255),calculated_at TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE project_well_productivity_exponential_output_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,output_id BIGINT,curve_type VARCHAR(32),point_number INT,source_point_number INT,x_value DOUBLE,y_value DOUBLE,is_deleted BOOLEAN,data_label VARCHAR(255))");
        jdbc.execute("CREATE TABLE project_well_productivity_exponential_ipr_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,output_id BIGINT,curve_number INT,formation_pressure DOUBLE,point_number INT,gas_production DOUBLE,bottom_hole_flowing_pressure DOUBLE,is_deleted BOOLEAN,data_label VARCHAR(255))");
        jdbc.update("INSERT INTO project_well_heads(project_id,project_gas_reservoir_id,well_name) VALUES (6,4,'A1-3')");
        jdbc.update("INSERT INTO project_well_pvt(well_id) VALUES (1)");
        service = new ProductivityTestService(jdbc);
    }

    @Test
    void preservesBothResultFamiliesAndPressureFormsUntilInputChanges() {
        long testId = service.save(request(null, true, binomial("pseudo-pressure"))).testId();
        service.save(request(testId, false, exponential("pseudo-pressure")));
        service.save(request(testId, false, exponential("pressure")));

        assertThat(service.detail(testId, "binomial", "pseudo-pressure").result().calculationResultType())
                .isEqualTo("binomial");
        assertThat(service.detail(testId, "exponential", "pseudo-pressure").result().productivityExponent())
                .isEqualTo(.75);
        assertThat(service.detail(testId, null, null).availableResults()).hasSize(3);

        service.save(request(testId, true, exponential("pressure-squared")));
        Detail latest = service.detail(testId, null, null);
        assertThat(latest.availableResults()).singleElement().satisfies(item -> {
            assertThat(item.calculationResultType()).isEqualTo("exponential");
            assertThat(item.pressureMethod()).isEqualTo("pressure-squared");
        });
        assertThat(latest.result().pressureMethod()).isEqualTo("pressure-squared");
    }

    private SaveRequest request(Long testId, boolean replaceInput, Result result) {
        Input input = new Input(56.34, 120d, null, "干气", .7336, 0d, 0d, 0d,
                0d, "0", "0", "0");
        List<InputItem> items = List.of(new InputItem(1, 45d, 35.61, 34.93),
                new InputItem(2, 59.1, 35.605, 34.65), new InputItem(3, 29.5, 35.68, 35.21));
        return new SaveRequest(testId, 6, 4, "A1-3", 1, "production", "modified-isochronal",
                null, LocalDate.of(2026, 8, 30), null, replaceInput, input, items, result);
    }

    private Result binomial(String method) {
        return new Result("binomial", method, 170L, 1d, 2d, 300d, null, null,
                1d, 0d, .95, 2, "可靠", List.of(), List.of());
    }

    private Result exponential(String method) {
        List<ChartPoint> charts = List.of(new ChartPoint("analysis", 1, 45d, 2d, false, "unstable"),
                new ChartPoint("analysis", 2, 29.5, 1.5, false, "stable"),
                new ChartPoint("transient", 1, 1d, 1d, false, null),
                new ChartPoint("regression", 1, 1d, 1.2, false, null));
        List<IprPoint> ipr = List.of(new IprPoint(1, 1, 0d, 5.634, false, null, 5.634));
        return new Result("exponential", method, null, null, null, 350d, 3d, .75,
                null, null, .96, 2, "可靠", charts, ipr);
    }
}
