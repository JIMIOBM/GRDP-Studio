using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseThermalPlanValues(double SurfaceInjectionRate, double InjectionTemperature, double SteamQuality, double BhpUpperLimit);
internal sealed record EclipseThermalPlanStage(int StartTimeDays, double SurfaceInjectionRate, double InjectionTemperature, double SteamQuality, double BhpUpperLimit)
{
    internal EclipseThermalPlanValues Values => new(SurfaceInjectionRate, InjectionTemperature, SteamQuality, BhpUpperLimit);
}
internal sealed record EclipseThermalPlanParameters(long BaselineRunId, string Well, string SourceFile, string SourceSha256,
    int LineNumber, int ControlLineNumber, string TemperatureUnit, string RateUnit, string PressureUnit,
    EclipseThermalPlanValues Original, IReadOnlyList<EclipseThermalPlanStage> Stages)
{
    internal const string Schema = "eclipse-thermal-injection-plan-parameters/1";
    private static readonly string[] ValueFields = ["surfaceInjectionRate", "injectionTemperature", "steamQuality", "bhpUpperLimit"];
    private static bool Shape(JsonElement n, params string[] names) => n.ValueKind == JsonValueKind.Object
        && n.EnumerateObject().Count() == names.Length && n.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names);
    private static bool Text(JsonElement n, string name, string pattern) => n.GetProperty(name).ValueKind == JsonValueKind.String
        && Regex.IsMatch(n.GetProperty(name).GetString()!, pattern, RegexOptions.CultureInvariant);
    private static bool Integer(JsonElement n, string name, long min, long max) => n.GetProperty(name).ValueKind == JsonValueKind.Number
        && n.GetProperty(name).TryGetInt64(out var value) && value >= min && value <= max;
    private static bool Values(JsonElement n) => ValueFields.All(k => n.GetProperty(k).ValueKind == JsonValueKind.Number
        && n.GetProperty(k).TryGetDouble(out var value) && double.IsFinite(value));
    private static EclipseThermalPlanValues ReadValues(JsonElement n) => new(n.GetProperty("surfaceInjectionRate").GetDouble(),
        n.GetProperty("injectionTemperature").GetDouble(), n.GetProperty("steamQuality").GetDouble(), n.GetProperty("bhpUpperLimit").GetDouble());
    internal static bool ValidValues(EclipseThermalPlanValues v, string temperature, string rate, string pressure) =>
        EclipseThermalInjectionParameters.Value("SURFACE_INJECTION_RATE", rate, v.SurfaceInjectionRate)
        && EclipseThermalInjectionParameters.Value("INJECTION_TEMPERATURE", temperature, v.InjectionTemperature)
        && EclipseThermalInjectionParameters.Value("STEAM_QUALITY", "fraction", v.SteamQuality)
        && EclipseThermalInjectionParameters.Value("BHP_UPPER_LIMIT", pressure, v.BhpUpperLimit);
    internal static bool Valid(EclipseThermalPlanParameters p)
    {
        if (p.Stages.Count is < 2 or > 8 || !((p.TemperatureUnit == "degF" && p.RateUnit == "STB/day" && p.PressureUnit == "psia")
            || (p.TemperatureUnit == "degC" && p.RateUnit == "sm3/day" && p.PressureUnit == "barsa"))
            || !ValidValues(p.Original, p.TemperatureUnit, p.RateUnit, p.PressureUnit)) return false;
        var previous = p.Original; var day = 0;
        foreach (var stage in p.Stages)
        {
            if (stage.StartTimeDays <= day || stage.StartTimeDays > 1_000_000_000 || stage.Values == previous
                || !ValidValues(stage.Values, p.TemperatureUnit, p.RateUnit, p.PressureUnit)) return false;
            day = stage.StartTimeDays; previous = stage.Values;
        }
        return true;
    }
    internal static bool TryParse(JsonElement n, out EclipseThermalPlanParameters? result)
    {
        result = null;
        if (!Shape(n, "schemaVersion", "baselineRunId", "well", "sourceFile", "sourceSha256", "lineNumber", "controlLineNumber",
                "temperatureUnit", "rateUnit", "pressureUnit", "original", "stages")
            || !Text(n,"schemaVersion", "^eclipse-thermal-injection-plan-parameters/1$")
            || !Integer(n,"baselineRunId",1,long.MaxValue) || !Integer(n,"lineNumber",1,int.MaxValue)
            || !Integer(n,"controlLineNumber",1,n.GetProperty("lineNumber").GetInt64()-1)
            || !Text(n,"well","^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$")
            || !Text(n,"sourceFile","^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || !Text(n,"sourceSha256","^[a-f0-9]{64}$") || !Text(n,"temperatureUnit","^(degF|degC)$")
            || !Text(n,"rateUnit","^(STB/day|sm3/day)$") || !Text(n,"pressureUnit","^(psia|barsa)$")
            || !Shape(n.GetProperty("original"), ValueFields) || !Values(n.GetProperty("original"))
            || n.GetProperty("stages").ValueKind != JsonValueKind.Array || n.GetProperty("stages").GetArrayLength() is < 2 or > 8) return false;
        var stages = new List<EclipseThermalPlanStage>();
        foreach (var s in n.GetProperty("stages").EnumerateArray())
        {
            if (!Shape(s, ["startTimeDays", .. ValueFields]) || !Integer(s,"startTimeDays",1,1_000_000_000) || !Values(s)) return false;
            var v = ReadValues(s);
            stages.Add(new(s.GetProperty("startTimeDays").GetInt32(), v.SurfaceInjectionRate, v.InjectionTemperature, v.SteamQuality, v.BhpUpperLimit));
        }
        var candidate = new EclipseThermalPlanParameters(n.GetProperty("baselineRunId").GetInt64(),n.GetProperty("well").GetString()!,
            n.GetProperty("sourceFile").GetString()!, n.GetProperty("sourceSha256").GetString()!, n.GetProperty("lineNumber").GetInt32(),
            n.GetProperty("controlLineNumber").GetInt32(), n.GetProperty("temperatureUnit").GetString()!, n.GetProperty("rateUnit").GetString()!,
            n.GetProperty("pressureUnit").GetString()!, ReadValues(n.GetProperty("original")),stages);
        if (!Valid(candidate)) return false;
        result = candidate; return true;
    }
}
