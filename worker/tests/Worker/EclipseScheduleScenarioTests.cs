using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseScheduleScenarioTests
{
    [Fact]
    public void ForecastParametersRequireAValidatedDataFileAndRestartArtifactReference()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-history-forecast-parameters/1\",\"historyRunId\":88,\"forecastDataFile\":\"EX3_PRED.DATA\",\"restartArtifactName\":\"eclipse-output-EX3.FUNRST\",\"restartArtifactSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"restartReport\":41}");
        using var invalid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-history-forecast-parameters/1\",\"historyRunId\":88,\"forecastDataFile\":\"../EX3_PRED.DATA\",\"restartArtifactName\":\"C:\\\\temp\\\\restart.FUNRST\",\"restartArtifactSha256\":\"bad\",\"restartReport\":41}");

        Assert.True(EclipseHistoryForecastParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal(88, parameters!.HistoryRunId);
        Assert.Equal("EX3_PRED.DATA", parameters.ForecastDataFile);
        Assert.Equal("eclipse-output-EX3.FUNRST", parameters.RestartArtifactName);
        Assert.False(EclipseHistoryForecastParameters.TryParse(invalid.RootElement, out _));
    }

    [Fact]
    public void ParametersRequireTheClosedWholeWellContract()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\"}");
        using var invalid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"extra\":1}");

        Assert.True(EclipseScheduleParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal("G1", parameters!.Well);
        Assert.Equal(new DateOnly(1970, 1, 15), parameters.Date);
        Assert.False(EclipseScheduleParameters.TryParse(invalid.RootElement, out _));
    }

    [Fact]
    public void ParametersAcceptOnlyTheControlledWconHistOratContract()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
        using var invalid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\",\"controlMode\":\"WRAT\",\"targetOilRate\":55.5}");

        Assert.True(EclipseScheduleParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal("ORAT", parameters!.ControlMode);
        Assert.Equal(55.5, parameters.TargetOilRate);
        Assert.False(EclipseScheduleParameters.TryParse(invalid.RootElement, out _));
    }

    [Fact]
    public void ParametersAcceptOnlyTheControlledWconInjeRateContract()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D1\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500}");
        using var invalid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D1\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"BHP\",\"targetInjectionRate\":1500}");

        Assert.True(EclipseScheduleParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal("RATE", parameters!.ControlMode);
        Assert.Equal("WATER", parameters.InjectionType);
        Assert.Equal(1500, parameters.TargetInjectionRate);
        Assert.False(EclipseScheduleParameters.TryParse(invalid.RootElement, out _));
    }

    [Fact]
    public void ParametersAcceptOnlyTheForecastInitialWconProdOratContract()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"FORECAST_INITIAL\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":4500}");
        using var invalid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"FORECAST_INITIAL\",\"status\":\"OPEN\",\"controlMode\":\"WRAT\",\"targetOilRate\":4500}");

        Assert.True(EclipseScheduleParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal("FORECAST_INITIAL", parameters!.Phase);
        Assert.Null(parameters.Date);
        Assert.Equal("ORAT", parameters.ControlMode);
        Assert.False(EclipseScheduleParameters.TryParse(invalid.RootElement, out _));
    }

    [Fact]
    public void CompletionParametersBindOneInspectedSourceRow()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-completion-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"sourceFile\":\"BASE.SCH\",\"lineNumber\":116,\"i\":\"14\",\"j\":\"2\",\"k1\":\"1\",\"k2\":\"1\",\"status\":\"SHUT\"}");
        using var invalid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-completion-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"sourceFile\":\"../BASE.SCH\",\"lineNumber\":116,\"i\":\"14\",\"j\":\"2\",\"k1\":\"1\",\"k2\":\"1\",\"status\":\"SHUT\"}");

        Assert.True(EclipseCompletionParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal("BASE.SCH", parameters!.SourceFile);
        Assert.Equal("SHUT", parameters.Status);
        Assert.False(EclipseCompletionParameters.TryParse(invalid.RootElement, out _));
    }

    [Fact]
    public void CompletionFactorParametersRequireAChangedPositiveNumericTarget()
    {
        using var valid = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-completion-factor-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"sourceFile\":\"BASE.SCH\",\"lineNumber\":116,\"i\":\"14\",\"j\":\"2\",\"k1\":\"1\",\"k2\":\"1\",\"originalConnectionFactor\":3.028,\"targetConnectionFactor\":1.5}");
        using var same = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-completion-factor-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"sourceFile\":\"BASE.SCH\",\"lineNumber\":116,\"i\":\"14\",\"j\":\"2\",\"k1\":\"1\",\"k2\":\"1\",\"originalConnectionFactor\":3.028,\"targetConnectionFactor\":3.028}");

        Assert.True(EclipseCompletionFactorParameters.TryParse(valid.RootElement, out var parameters));
        Assert.Equal(3.028, parameters!.OriginalConnectionFactor);
        Assert.Equal(1.5, parameters.TargetConnectionFactor);
        Assert.False(EclipseCompletionFactorParameters.TryParse(same.RootElement, out _));
    }

    [Fact]
    public async Task EditorChangesOnlyTheMatchingDateAndWellRow()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE.SCH");
            await File.WriteAllTextAsync(schedule, "DATES\n15 'JAN' 1970/\n/\nWELOPEN\n 'G1' 'SHUT' 0 0 0 /\n 'G2' 'OPEN' 0 0 0 /\n/\nDATES\n1 'FEB' 1970/\n/\nWELOPEN\n 'G1' 'OPEN' 0 0 0 /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\"}");
            Assert.True(EclipseScheduleParameters.TryParse(document.RootElement, out var parameters));

            var message = await EclipseScheduleEditor.ApplyAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken);
            var text = await File.ReadAllTextAsync(schedule, TestContext.Current.CancellationToken);

            Assert.Contains("Applied WELOPEN G1=OPEN at 1970-01-15", message);
            Assert.Contains("'G1' 'OPEN' 0 0 0", text);
            Assert.Contains("'G1' 'OPEN' 0 0 0 /", text);
            Assert.Contains("DATES\n1 'FEB' 1970/", text);
            Assert.DoesNotContain("\r\n", text);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task EditorRejectsAnExistingDateWithoutTheRequestedWellRow()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE.SCH");
            await File.WriteAllTextAsync(schedule, "DATES\n15 'JAN' 1970/\n/\nWELOPEN\n 'G2' 'SHUT' 0 0 0 /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"OPEN\"}");
            Assert.True(EclipseScheduleParameters.TryParse(document.RootElement, out var parameters));

            var exception = await Assert.ThrowsAsync<EclipseDeckPackageException>(() =>
                EclipseScheduleEditor.ApplyAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken));

            Assert.Equal("ECLIPSE_SCHEDULE_TARGET_MISSING", exception.Code);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task EditorChangesOnlyTheMatchingWconHistOratRow()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE.SCH");
            await File.WriteAllTextAsync(schedule, "DATES\n15 'JAN' 1970/\n/\nWCONHIST\n 'G1' 'OPEN' 'ORAT' 35.686 0.119 45436.102 4* /\n 'G2' 'OPEN' 'ORAT' 749.610 2.860 1115.620 4* /\n/\nDATES\n1 'FEB' 1970/\n/\nWCONHIST\n 'G1' 'OPEN' 'ORAT' 64.598 0.231 80094.703 4* /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/2\",\"baselineRunId\":74,\"well\":\"G1\",\"date\":\"1970-01-15\",\"status\":\"SHUT\",\"controlMode\":\"ORAT\",\"targetOilRate\":55.5}");
            Assert.True(EclipseScheduleParameters.TryParse(document.RootElement, out var parameters));

            var message = await EclipseScheduleEditor.ApplyAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken);
            var text = await File.ReadAllTextAsync(schedule, TestContext.Current.CancellationToken);

            Assert.Contains("Applied WCONHIST G1=ORAT 55.5 at 1970-01-15", message);
            Assert.Contains("'G1' 'SHUT' 'ORAT' 55.5 0.119 45436.102 4* /", text);
            Assert.Contains("'G2' 'OPEN' 'ORAT' 749.610 2.860 1115.620 4* /", text);
            Assert.Contains("'G1' 'OPEN' 'ORAT' 64.598 0.231 80094.703 4* /", text);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task EditorChangesOnlyTheMatchingWconInjeRateRow()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE.SCH");
            await File.WriteAllTextAsync(schedule, "DATES\n15 'JAN' 1970/\n/\nWCONINJE\n 'D1' 'WATER' 1* 'RATE' 2000.000 5* /\n 'D2' 'WATER' 1* 'RATE' 2000.000 5* /\n/\nDATES\n1 'FEB' 1970/\n/\nWCONINJE\n 'D1' 'WATER' 1* 'RATE' 0.000 5* /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/3\",\"baselineRunId\":74,\"well\":\"D1\",\"date\":\"1970-01-15\",\"injectionType\":\"WATER\",\"controlMode\":\"RATE\",\"targetInjectionRate\":1500.5}");
            Assert.True(EclipseScheduleParameters.TryParse(document.RootElement, out var parameters));

            var message = await EclipseScheduleEditor.ApplyAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken);
            var text = await File.ReadAllTextAsync(schedule, TestContext.Current.CancellationToken);

            Assert.Contains("Applied WCONINJE D1/WATER=RATE 1500.5 at 1970-01-15", message);
            Assert.Contains("'D1' 'WATER' 1* 'RATE' 1500.5 5* /", text);
            Assert.Contains("'D2' 'WATER' 1* 'RATE' 2000.000 5* /", text);
            Assert.Contains("DATES\n1 'FEB' 1970/", text);
            Assert.Contains("'D1' 'WATER' 1* 'RATE' 0.000 5* /", text);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task EditorChangesOnlyTheForecastInitialWconProdOratRow()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE_PRED.SCH");
            await File.WriteAllTextAsync(schedule, "DATES\n1 'JAN' 1974/\n/\nWCONPROD\n 'HORW2' 'SHUT' 'ORAT' 5000.000 4* 500.000 3* /\n 'HORW3' 'SHUT' 'ORAT' 10000.000 4* 200.000 3* /\n/\nDATES\n1 'JUN' 1974/\n/\nWCONPROD\n 'HORW2' 'SHUT' 'ORAT' 6000.000 4* 500.000 3* /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-schedule-parameters/4\",\"baselineRunId\":74,\"well\":\"HORW2\",\"phase\":\"FORECAST_INITIAL\",\"status\":\"OPEN\",\"controlMode\":\"ORAT\",\"targetOilRate\":4500.5}");
            Assert.True(EclipseScheduleParameters.TryParse(document.RootElement, out var parameters));

            var message = await EclipseScheduleEditor.ApplyAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken);
            var text = await File.ReadAllTextAsync(schedule, TestContext.Current.CancellationToken);

            Assert.Contains("Applied WCONPROD HORW2=ORAT 4500.5 at FORECAST_INITIAL", message);
            Assert.Contains("'HORW2' 'OPEN' 'ORAT' 4500.5 4* 500.000 3* /", text);
            Assert.Contains("'HORW3' 'SHUT' 'ORAT' 10000.000 4* 200.000 3* /", text);
            Assert.Contains("DATES\n1 'JUN' 1974/", text);
            Assert.Contains("'HORW2' 'SHUT' 'ORAT' 6000.000 4* 500.000 3* /", text);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task EditorChangesOnlyTheInspectedCompdatRow()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE.SCH");
            await File.WriteAllTextAsync(schedule, "COMPDAT\n 'G1' 14 2 1 1 'OPEN' 1* 3.028 0.656 /\n 'G1' 14 2 2 2 'OPEN' 1* 4.675 0.656 /\n/\nCOMPDATM\n 'G1' 14 2 3 3 'OPEN' 1* /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-completion-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"sourceFile\":\"BASE.SCH\",\"lineNumber\":2,\"i\":\"14\",\"j\":\"2\",\"k1\":\"1\",\"k2\":\"1\",\"status\":\"SHUT\"}");
            Assert.True(EclipseCompletionParameters.TryParse(document.RootElement, out var parameters));

            var message = await EclipseScheduleEditor.ApplyCompletionAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken);
            var text = await File.ReadAllTextAsync(schedule, TestContext.Current.CancellationToken);

            Assert.Contains("Applied COMPDAT G1 I=14 J=2 K=1-1=SHUT at BASE.SCH:2", message);
            Assert.Contains("'G1' 14 2 1 1 'SHUT' 1* 3.028 0.656 /", text);
            Assert.Contains("'G1' 14 2 2 2 'OPEN' 1* 4.675 0.656 /", text);
            Assert.Contains("COMPDATM\n 'G1' 14 2 3 3 'OPEN' 1* /", text);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task EditorChangesOnlyTheInspectedCompdatConnectionFactor()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-scenario-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var schedule = Path.Combine(root, "BASE.SCH");
            await File.WriteAllTextAsync(schedule, "COMPDAT\n 'G1' 14 2 1 1 'OPEN' 1* 3.028 0.656 /\n 'G1' 14 2 2 2 'OPEN' 1* 4.675 0.656 /\n/\nCOMPDATM\n 'G1' 14 2 3 3 'OPEN' 1* /\n/\n", TestContext.Current.CancellationToken);
            using var document = JsonDocument.Parse("{\"schemaVersion\":\"eclipse-completion-factor-parameters/1\",\"baselineRunId\":74,\"well\":\"G1\",\"sourceFile\":\"BASE.SCH\",\"lineNumber\":2,\"i\":\"14\",\"j\":\"2\",\"k1\":\"1\",\"k2\":\"1\",\"originalConnectionFactor\":3.028,\"targetConnectionFactor\":1.5}");
            Assert.True(EclipseCompletionFactorParameters.TryParse(document.RootElement, out var parameters));

            var message = await EclipseScheduleEditor.ApplyCompletionFactorAsync(new(schedule, root, [schedule]), parameters!, TestContext.Current.CancellationToken);
            var text = await File.ReadAllTextAsync(schedule, TestContext.Current.CancellationToken);

            Assert.Contains("Applied COMPDAT CF G1 I=14 J=2 K=1-1 3.028->1.5 at BASE.SCH:2", message);
            Assert.Contains("'G1' 14 2 1 1 'OPEN' 1* 1.5 0.656 /", text);
            Assert.Contains("'G1' 14 2 2 2 'OPEN' 1* 4.675 0.656 /", text);
            Assert.Contains("COMPDATM\n 'G1' 14 2 3 3 'OPEN' 1* /", text);
        }
        finally { Directory.Delete(root, true); }
    }
}
