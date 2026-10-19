using System.Text.Json;
using System.Text.Json.Serialization;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record EclipsePortableRestartBinding(string SchemaVersion, string PhysicsSha256, EclipseRestartCatalog Catalog)
{
    public const string Filename = "eclipse-restart-binding.json";
    private static readonly JsonSerializerOptions Options = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow };
    public static string? Validate(EclipseDeckPackage package)
    {
        var path = Path.Combine(package.Root, Filename);
        if (!File.Exists(path)) return null; // Legacy packages have their existing execution path.
        if (new FileInfo(path).Length is <= 0 or > 64 * 1024) throw new InvalidDataException("Invalid portable restart binding size.");
        EclipsePortableRestartBinding binding;
        try
        {
            var text = File.ReadAllText(path);
            using var document = JsonDocument.Parse(text);
            NoDuplicateFields(document.RootElement);
            binding = JsonSerializer.Deserialize<EclipsePortableRestartBinding>(text, Options) ?? throw new JsonException();
        }
        catch (JsonException e) { throw new InvalidDataException("Invalid portable restart binding.", e); }
        var source = EclipseForecastSourceReader.Read(package, true, EclipseRestartReader.IsBinary(binding.Catalog));
        var binary = EclipseRestartReader.IsBinary(binding.Catalog);
        if (binding.SchemaVersion != (binary ? "eclipse-portable-restart/2" : "eclipse-portable-restart/1")
            || (source.RestartFormat == "BINARY") != binary || binding.Catalog is null || binding.Catalog.Reports is null
            || binding.Catalog.Reports.Count != 1 || binding.Catalog.Reports[0] is null || source.PhysicsSha256 != binding.PhysicsSha256 || source.StartDate != binding.Catalog.StartDate
            || source.RestartCase != binding.Catalog.CaseName || source.RestartReport != binding.Catalog.Reports[0].ReportNumber
            || source.FutureDates.Any(date => string.CompareOrdinal(date, binding.Catalog.Reports[0].Date) <= 0))
            throw new InvalidDataException("Portable forecast source does not match its selected historical state.");
        var restart = Path.Combine(package.Root, source.RestartCase + (binary ? ".UNRST" : ".FUNRST"));
        _ = EclipseRestartReader.Select(restart, binding.Catalog, source.RestartReport);
        return restart;
    }
    private static void NoDuplicateFields(JsonElement value)
    {
        if (value.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in value.EnumerateObject()) { if (!names.Add(property.Name)) throw new JsonException("Duplicate portable binding field."); NoDuplicateFields(property.Value); }
        }
        else if (value.ValueKind == JsonValueKind.Array) foreach (var item in value.EnumerateArray()) NoDuplicateFields(item);
    }
}
