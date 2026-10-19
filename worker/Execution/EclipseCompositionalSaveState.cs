using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Storage;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseCompositionalBoundState(EclipseCompositionalSaveSourceReader.Snapshot Source,
    EclipseCompositionalSaveCatalog Catalog, IReadOnlyList<string> StateFiles, double? TargetGasRate);
internal sealed record EclipseCompositionalPortableBinding(string SchemaVersion, EclipseCompositionalSaveCatalog Catalog,
    string ForecastFile, string ForecastSha256, double? TargetGasRate);

internal static class EclipseCompositionalSaveState
{
    internal const string BindingFile = "eclipse-compositional-restart-binding.json";
    internal const string CatalogFile = "eclipse-compositional-save-catalog.json";
    private static readonly JsonSerializerOptions Json = new() {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase, UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        NumberHandling = JsonNumberHandling.Strict
    };
    private static bool SafeSource(string? name) => name is not null && Regex.IsMatch(name,
        @"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    internal static EclipseSimulationModel Simulation(EclipseCompositionalBoundState state) => new(300, "COMPOSITIONAL", 300,
        JsonSerializer.SerializeToElement(new EclipseSimulationMetadata(300, "COMPOSITIONAL", state.Source.Production.ComponentCount,
            state.Source.Production.ComponentNames, "PR")));
    private static string[] StateNames(string source, int report) => [Path.GetFileNameWithoutExtension(source) + ".V0000",
        Path.GetFileNameWithoutExtension(source) + ".V" + report.ToString("D4", System.Globalization.CultureInfo.InvariantCulture),
        Path.GetFileNameWithoutExtension(source) + ".X" + report.ToString("D4", System.Globalization.CultureInfo.InvariantCulture)];

    internal static async Task<EclipseCompositionalBoundState> LoadPublishedAsync(StorageResolver storage,
        EclipseCompositionalForecastParameters parameters, EclipseDeckPackage sourcePackage, CancellationToken cancellationToken)
    {
        var source = EclipseCompositionalSaveSourceReader.Read(sourcePackage);
        if (!parameters.MatchesSource(source)) throw new InvalidDataException("Forecast does not match the qualified historical source and report.");
        var path = storage.ResolvePublishedCompositionalSaveArtifact(parameters.HistoryRunId, CatalogFile);
        await using var guard = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous);
        if (guard.Length is < 1 or > 64 * 1024) throw new InvalidDataException("Historical catalog exceeds the verified bound.");
        var bytes = new byte[checked((int)guard.Length)]; await guard.ReadExactlyAsync(bytes, cancellationToken);
        if (Convert.ToHexStringLower(SHA256.HashData(bytes)) != parameters.SaveCatalogSha256)
            throw new InvalidDataException("Historical catalog checksum no longer matches its selected artifact.");
        var catalog = ReadJson<EclipseCompositionalSaveCatalog>(bytes);
        var files = StateNames(parameters.SourceFile, parameters.SaveReport).Select(n => storage.ResolvePublishedCompositionalSaveArtifact(parameters.HistoryRunId, "eclipse-output-" + n)).ToArray();
        var actual = EclipseCompositionalSaveCatalogReader.Read(sourcePackage, files, "2024.1", true);
        SameCatalog(catalog, actual);
        return new(source!, actual, files, parameters.TargetGasRate);
    }

    internal static EclipseCompositionalBoundState? ReadPortable(EclipseDeckPackage package)
    {
        var bindingPath = Path.Combine(package.Root, BindingFile);
        if (!File.Exists(bindingPath)) return null;
        NoReparse(package.Root, bindingPath);
        if (new FileInfo(bindingPath).Length is < 1 or > 64 * 1024) throw new InvalidDataException("Invalid compositional portable binding size.");
        var binding = ReadJson<EclipseCompositionalPortableBinding>(File.ReadAllBytes(bindingPath));
        var c = binding.Catalog;
        if (binding.SchemaVersion != "eclipse-compositional-portable-restart/1" || c is null || !SafeSource(c.SourceFile)
            || c.ReportNumber is < 1 or > 9999 || c.Solver != 300 || c.SolverVersion != "2024.1"
            || !SafeSource(binding.ForecastFile) || binding.ForecastFile != Path.GetFileName(package.MainFile)
            || Path.GetFullPath(package.MainFile) != Path.GetFullPath(Path.Combine(package.Root, binding.ForecastFile)))
            throw new InvalidDataException("Portable forecast identity, solver or release is invalid.");
        var names = StateNames(c.SourceFile, c.ReportNumber);
        var expected = new[] { binding.ForecastFile, BindingFile, "history/input/" + c.SourceFile }
            .Concat(names.Select(n => "history/state/" + n)).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var actualFiles = Directory.EnumerateFiles(package.Root, "*", new EnumerationOptions {
            RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint
        }).Take(7).ToArray();
        if (actualFiles.Length != 6 || !expected.SetEquals(actualFiles.Select(p => Path.GetRelativePath(package.Root, p).Replace('\\', '/'))))
            throw new InvalidDataException("Portable forecast must contain the complete exact six-file source and state package.");
        if (actualFiles.Sum(p => new FileInfo(p).Length) > 64L * 1024 * 1024)
            throw new InvalidDataException("Portable compositional state exceeds the 64 MiB verified package bound.");
        foreach (var path in actualFiles) NoReparse(package.Root, path);
        var input = Path.Combine(package.Root, "history/input");
        var historical = new EclipseDeckPackageResolver().Resolve(Path.Combine(input, c.SourceFile), input);
        var source = EclipseCompositionalSaveSourceReader.Read(historical) ?? throw new InvalidDataException("Portable historical input is outside the verified SAVE subset.");
        if (binding.TargetGasRate is not null && (!double.IsFinite(binding.TargetGasRate.Value) || binding.TargetGasRate is <= 0 or > 100_000_000
            || binding.TargetGasRate == source.Production.Records.Single().OriginalGasRate))
            throw new InvalidDataException("Portable forecast target is invalid or unchanged.");
        var files = names.Select(n => Path.Combine(package.Root, "history/state", n)).ToArray();
        var recomputed = EclipseCompositionalSaveCatalogReader.Read(historical, files, "2024.1");
        SameCatalog(c, recomputed);
        var raw = File.ReadAllBytes(package.MainFile);
        if (binding.ForecastSha256 != Convert.ToHexStringLower(SHA256.HashData(raw)) || !raw.SequenceEqual(
            EclipseCompositionalSaveSourceReader.BuildForecast(source, binding.TargetGasRate, "history/state/" + c.CaseName)))
            throw new InvalidDataException("Portable forecast differs from the exact selected historical control and future clock.");
        return new(source, recomputed, files, binding.TargetGasRate);
    }

    internal static async Task<EclipseCompositionalBoundState> PreparePublishedAsync(StorageResolver storage,
        EclipseCompositionalForecastParameters parameters, EclipseDeckPackage workPackage, CancellationToken cancellationToken)
    {
        var state = await LoadPublishedAsync(storage, parameters, workPackage, cancellationToken);
        var input = Path.Combine(workPackage.Root, "history/input"); var saved = Path.Combine(workPackage.Root, "history/state");
        if (Directory.Exists(Path.Combine(workPackage.Root, "history"))) throw new InvalidDataException("Forecast history workspace already exists.");
        Directory.CreateDirectory(input); Directory.CreateDirectory(saved);
        await File.WriteAllBytesAsync(Path.Combine(input, state.Catalog.SourceFile), state.Source.OriginalBytes, cancellationToken);
        for (var j = 0; j < state.StateFiles.Count; j++)
        {
            var expected = state.Catalog.Files[j];
            await using var guard = new FileStream(state.StateFiles[j], FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous);
            if (guard.Length != expected.SizeBytes || Convert.ToHexStringLower(await SHA256.HashDataAsync(guard, cancellationToken)) != expected.Sha256)
                throw new InvalidDataException("Historical state changed before isolated forecast preparation.");
            guard.Position = 0;
            await using var target = new FileStream(Path.Combine(saved, expected.Name), FileMode.CreateNew, FileAccess.Write, FileShare.None,
                64 * 1024, FileOptions.Asynchronous);
            await guard.CopyToAsync(target, cancellationToken);
        }
        var data = EclipseCompositionalSaveSourceReader.BuildForecast(state.Source, parameters.TargetGasRate, "history/state/" + state.Catalog.CaseName);
        await File.WriteAllBytesAsync(workPackage.MainFile, data, cancellationToken);
        var binding = new EclipseCompositionalPortableBinding("eclipse-compositional-portable-restart/1", state.Catalog,
            Path.GetFileName(workPackage.MainFile), Convert.ToHexStringLower(SHA256.HashData(data)), parameters.TargetGasRate);
        await File.WriteAllBytesAsync(Path.Combine(workPackage.Root, BindingFile), JsonSerializer.SerializeToUtf8Bytes(binding, Json), cancellationToken);
        return ReadPortable(workPackage) ?? throw new InvalidDataException("Prepared forecast did not retain its complete source binding.");
    }

    private static void SameCatalog(EclipseCompositionalSaveCatalog expected, EclipseCompositionalSaveCatalog actual)
    {
        try
        {
            if (!JsonSerializer.SerializeToUtf8Bytes(expected, Json).SequenceEqual(JsonSerializer.SerializeToUtf8Bytes(actual, Json)))
                throw new InvalidDataException("Historical catalog does not match the actual source, grid, component identities, clock and file bytes.");
        }
        catch (ArgumentException e) { throw new InvalidDataException("Invalid catalog numeric metadata.", e); }
    }
    private static T ReadJson<T>(byte[] bytes)
    {
        try
        {
            using var document = JsonDocument.Parse(bytes); NoDuplicates(document.RootElement);
            return JsonSerializer.Deserialize<T>(bytes, Json) ?? throw new JsonException();
        }
        catch (JsonException e) { throw new InvalidDataException("Invalid closed compositional state binding.", e); }
    }
    private static void NoDuplicates(JsonElement node)
    {
        if (node.ValueKind == JsonValueKind.Object)
        {
            var fields = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in node.EnumerateObject()) { if (!fields.Add(property.Name)) throw new JsonException("Duplicate binding field."); NoDuplicates(property.Value); }
        }
        else if (node.ValueKind == JsonValueKind.Array) foreach (var item in node.EnumerateArray()) NoDuplicates(item);
    }
    private static void NoReparse(string root, string file)
    {
        var current = Path.GetFullPath(file); var boundary = Path.GetFullPath(root);
        while (current.StartsWith(boundary, StringComparison.OrdinalIgnoreCase))
        {
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new InvalidDataException("Portable historical state cannot traverse reparse points.");
            if (current.Equals(boundary, StringComparison.OrdinalIgnoreCase)) return;
            current = Path.GetDirectoryName(current) ?? throw new InvalidDataException("Invalid package boundary.");
        }
        throw new InvalidDataException("Portable historical state escapes the package.");
    }
}
