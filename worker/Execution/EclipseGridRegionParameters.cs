using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseGridRegionParameters(long BaselineRunId, string FieldFile, string FieldArtifactSha256,
    string GridFile, string GridArtifactSha256, string Keyword, int Nx, int Ny, int Nz,
    int I1, int I2, int J1, int J2, int K1, int K2, double[] OriginalValues, double Multiplier)
{
    internal const string Schema = "eclipse-grid-region-parameters/1";
    internal const int MaxRegionCells = 4096;
    internal static bool TryParse(JsonElement value, out EclipseGridRegionParameters? parameters)
    {
        parameters = null;
        string[] names = ["schemaVersion", "baselineRunId", "fieldFile", "fieldArtifactSha256", "gridFile", "gridArtifactSha256",
            "keyword", "nx", "ny", "nz", "i1", "i2", "j1", "j2", "k1", "k2", "originalValues", "multiplier"];
        if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() != names.Length
            || !value.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)) return false;
        if (Text(value, "schemaVersion") != Schema || value.GetProperty("baselineRunId").ValueKind != JsonValueKind.Number
            || !value.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline <= 0) return false;
        var field = Text(value, "fieldFile"); var grid = Text(value, "gridFile"); var sha = Text(value, "fieldArtifactSha256"); var gridSha = Text(value, "gridArtifactSha256");
        var keyword = Text(value, "keyword");
        if (!FileName(field, "INIT") || !FileName(grid, "EGRID")
            || !Regex.IsMatch(sha, "^[0-9a-f]{64}$", RegexOptions.CultureInvariant)
            || !Regex.IsMatch(gridSha, "^[0-9a-f]{64}$", RegexOptions.CultureInvariant)
            || keyword is not ("PERMX" or "PERMY" or "PERMZ" or "PORO")) return false;
        var coords = new int[9];
        string[] integers = ["nx", "ny", "nz", "i1", "i2", "j1", "j2", "k1", "k2"];
        for (var index = 0; index < integers.Length; index++)
        {
            var item = value.GetProperty(integers[index]);
            if (item.ValueKind != JsonValueKind.Number || !item.TryGetInt32(out coords[index]) || coords[index] is <= 0 or > 2_000_000) return false;
        }
        var (nx, ny, nz, i1, i2, j1, j2, k1, k2) = (coords[0], coords[1], coords[2], coords[3], coords[4], coords[5], coords[6], coords[7], coords[8]);
        if ((long)nx * ny * nz > 2_000_000 || i2 < i1 || j2 < j1 || k2 < k1 || i2 > nx || j2 > ny || k2 > nz) return false;
        var count = (long)(i2 - i1 + 1) * (j2 - j1 + 1) * (k2 - k1 + 1);
        var raw = value.GetProperty("originalValues");
        var scalar = value.GetProperty("multiplier");
        if (count > MaxRegionCells || raw.ValueKind != JsonValueKind.Array || raw.GetArrayLength() != count
            || scalar.ValueKind != JsonValueKind.Number || !scalar.TryGetDouble(out var multiplier)
            || !double.IsFinite(multiplier) || multiplier is < 0.000001 or > 100 || multiplier == 1) return false;
        var originals = new double[(int)count]; var cursor = 0;
        foreach (var item in raw.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Number || !item.TryGetDouble(out var original) || !ValidPhysical(keyword, original, multiplier)) return false;
            originals[cursor++] = original;
        }
        parameters = new(baseline, field, sha, grid, gridSha, keyword, nx, ny, nz, i1, i2, j1, j2, k1, k2, originals, multiplier);
        return true;
    }
    internal static bool ValidPhysical(string keyword, double original, double multiplier) => double.IsFinite(original) && original > 0
        && double.IsFinite(original * multiplier) && original * multiplier > 0
        && (keyword != "PORO" || original <= 1 && original * multiplier <= 1);
    private static string Text(JsonElement value, string name) => value.GetProperty(name).ValueKind == JsonValueKind.String ? value.GetProperty(name).GetString()! : "";
    private static bool FileName(string value, string extension) => Regex.IsMatch(value, "^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\." + extension + "$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
}
