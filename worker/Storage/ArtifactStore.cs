using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.IO.Compression;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Storage;

public sealed class ArtifactStore
{
    private const string Redacted = "[redacted]";
    private static readonly Regex DrivePath = new(@"(?<![a-z0-9])[a-z]:[\\/][^\s\""']*", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex UncPath = new(@"(?<!\\)\\\\[^\\/\s]+\\[^\s\""']+", RegexOptions.CultureInvariant);
    private static readonly Regex UnixPath = new(@"(?<![a-z0-9:/])/(?:[^\s\""']+)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex FileUri = new(@"file://[^\s\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex LocalPipe = new(@"net\.pipe://[^\s\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex AuthorizationCredential = new(@"(?<prefix>\bauthorization\s*[:=]\s*)(?:(?:bearer|basic|token)\s+)?[^\s,;\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex BearerCredential = new(@"\bbearer\s+(?!(?:authentication|token|tokens|authorization|credential|credentials|scheme|header|required|missing|invalid|expired|unsupported|was|is|not)\b)[^\s,;\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex ApiTokenCredential = new(@"(?<prefix>\b(?:api[-_ ]?token|api[-_ ]?key|x-api-key)\s*[:=]\s*)[^\s,;\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex TokenCredential = new(@"(?<prefix>\b(?:access[-_ ]?token|refresh[-_ ]?token|token)\s*[:=]\s*)(?:\""[^\""\r\n]*\""|'[^'\r\n]*'|[^\s,;\""']+)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex CookieCredential = new(@"(?<prefix>\b(?:set-cookie|cookie)\s*[:=]\s*)[^\r\n]*", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex SensitiveKeyValueCredential = new(@"(?<prefix>\b(?:password|passwd|pwd|secret|private[-_ ]?key)\s*[:=]\s*)[^\s,;\""']+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex EclipseBinaryOutputName = new(@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.(?:EGRID|INIT|UNRST|UNSMRY|SMSPEC|FUNRST|S\d{4,5})$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly Regex VfpEngineFileName = new(@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$", RegexOptions.CultureInvariant);
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };
    private readonly StorageResolver storage;

    public ArtifactStore(StorageResolver storage) => this.storage = storage;

    public async Task<ArtifactDescriptor> WriteJsonAsync(string outputDirectory, string name, object value, CancellationToken cancellationToken = default)
    {
        var bytes = SerializeSanitized(JsonSerializer.SerializeToElement(value, JsonOptions));
        return await WriteBytesAsync(outputDirectory, name, bytes, "application/json", cancellationToken);
    }

    public async Task<ArtifactDescriptor> WriteJsonElementAsync(string outputDirectory, string name, JsonElement value, CancellationToken cancellationToken = default)
    {
        var bytes = SerializeSanitized(value);
        return await WriteBytesAsync(outputDirectory, name, bytes, "application/json", cancellationToken);
    }

    public async Task<ArtifactDescriptor> WriteLogAsync(string outputDirectory, IReadOnlyList<string> messages, CancellationToken cancellationToken = default)
    {
        var controlled = messages.Select(SanitizeLogMessage);
        var bytes = Encoding.UTF8.GetBytes(string.Join(Environment.NewLine, controlled) + Environment.NewLine);
        return await WriteBytesAsync(outputDirectory, "run.log", bytes, "text/plain", cancellationToken);
    }

    public async Task<ArtifactDescriptor> WritePipesimPwiAsync(string outputDirectory, string content, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(content) || content.Contains('\0') || Encoding.UTF8.GetByteCount(content) > 2_000_000)
            throw new InvalidDataException("The PIPESIM PWI content is empty or exceeds the approved size limit.");
        return await WriteBytesAsync(outputDirectory, "pipesim-network-well-performance.pwi",
            Encoding.UTF8.GetBytes(content), "text/plain; charset=utf-8", cancellationToken);
    }

    public async Task<ArtifactDescriptor> WritePipesimVfpTableAsync(
        string outputDirectory,
        string content,
        bool includeTemperature,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(content) || content.Contains('\0') || Encoding.UTF8.GetByteCount(content) > 2_000_000 ||
            !content.Contains("VFPPROD", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("The PIPESIM VFP table text is empty, invalid, or exceeds the approved size limit.");
        var name = includeTemperature ? "pipesim-vfp-table-temperature.vfpprod" : "pipesim-vfp-table.vfpprod";
        return await WriteBytesAsync(outputDirectory, name, Encoding.UTF8.GetBytes(content),
            "text/plain; charset=utf-8", cancellationToken);
    }

    public async Task<IReadOnlyList<ArtifactDescriptor>> CopyPipesimVfpEngineFilesAsync(
        string outputDirectory,
        string sourceDirectory,
        CancellationToken cancellationToken = default)
    {
        var sourceRoot = Path.GetFullPath(sourceDirectory);
        if (!Directory.Exists(sourceRoot) || (File.GetAttributes(sourceRoot) & FileAttributes.ReparsePoint) != 0)
            throw new InvalidDataException("The isolated PIPESIM VFP engine output directory is unavailable.");
        var manifestPath = Path.Combine(sourceRoot, "engine-files.json");
        if (!File.Exists(manifestPath) || (File.GetAttributes(manifestPath) & FileAttributes.ReparsePoint) != 0 ||
            new FileInfo(manifestPath).Length > 64 * 1024)
            throw new InvalidDataException("The PIPESIM VFP engine file manifest is unavailable or too large.");

        await using var manifestStream = File.OpenRead(manifestPath);
        using var manifest = await JsonDocument.ParseAsync(manifestStream, cancellationToken: cancellationToken);
        var root = manifest.RootElement;
        if (root.ValueKind != JsonValueKind.Object || root.EnumerateObject().Count() != 2 ||
            !root.TryGetProperty("schemaVersion", out var schema) || schema.GetString() != "pipesim-vfp-engine-files/1" ||
            !root.TryGetProperty("files", out var files) || files.ValueKind != JsonValueKind.Array || files.GetArrayLength() is < 1 or > 64)
            throw new InvalidDataException("The PIPESIM VFP engine file manifest is invalid.");

        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var descriptors = new List<ArtifactDescriptor>();
        long totalBytes = 0;
        foreach (var item in files.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.String) throw new InvalidDataException("The PIPESIM VFP engine file manifest contains an invalid name.");
            var name = item.GetString();
            if (string.IsNullOrWhiteSpace(name) || !VfpEngineFileName.IsMatch(name) || name is "." or ".." || !names.Add(name))
                throw new InvalidDataException("The PIPESIM VFP engine file manifest contains an unsafe or duplicate name.");
            var sourcePath = Path.GetFullPath(Path.Combine(sourceRoot, name));
            if (!string.Equals(Path.GetDirectoryName(sourcePath), sourceRoot, StringComparison.OrdinalIgnoreCase) ||
                !File.Exists(sourcePath) || (File.GetAttributes(sourcePath) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("A PIPESIM VFP engine file is outside the isolated output folder.");
            var size = new FileInfo(sourcePath).Length;
            totalBytes = checked(totalBytes + size);
            if (size is <= 0 or > 100_000_000 || totalBytes > 256_000_000)
                throw new InvalidDataException("PIPESIM VFP engine files exceed the artifact size limits.");

            var artifactName = "pipesim-vfp-engine-" + name;
            var destination = Path.Combine(outputDirectory, artifactName);
            var temporary = destination + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                await using (var input = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                    1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
                await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
                {
                    await input.CopyToAsync(output, cancellationToken);
                }
                File.Move(temporary, destination, overwrite: false);
            }
            finally
            {
                if (File.Exists(temporary)) File.Delete(temporary);
            }
            var actualSize = new FileInfo(destination).Length;
            descriptors.Add(new ArtifactDescriptor(storage.ToStorageKey(destination), actualSize,
                await ComputeFileSha256Async(destination, cancellationToken), "application/octet-stream"));
        }
        return descriptors;
    }

    public async Task<ArtifactDescriptor> WriteManifestAsync(
        string outputDirectory,
        long runId,
        IReadOnlyList<ArtifactDescriptor> files,
        CancellationToken cancellationToken = default)
    {
        var manifest = new
        {
            schemaVersion = "grdp-worker-artifact-manifest/1",
            runId,
            generatedAtUtc = DateTimeOffset.UtcNow,
            files = files.Select(file => new { file.StorageKey, file.Size, file.Sha256, file.ContentType }).ToArray()
        };
        return await WriteJsonAsync(outputDirectory, "manifest.json", manifest, cancellationToken);
    }

    // ECLIPSE text outputs can expose paths, host details, and license diagnostics.
    // Keep those as metadata only; selected binary result families are copied to a
    // controlled artifact name so the browser can download the real result package.
    public async Task<IReadOnlyList<EclipseOutputMetadata>> DescribeEclipseOutputsAsync(IEnumerable<string> files, CancellationToken cancellationToken = default)
    {
        var outputFiles = new List<EclipseOutputMetadata>();
        foreach (var file in files)
        {
            var info = new FileInfo(file);
            outputFiles.Add(new(Path.GetFileName(file), info.Length, await ComputeFileSha256Async(file, cancellationToken)));
        }
        return outputFiles.OrderBy(file => file.Filename, StringComparer.OrdinalIgnoreCase).ToArray();
    }

    public async Task<ArtifactDescriptor> CopyEclipseBinaryOutputAsync(
        string outputDirectory,
        string sourcePath,
        CancellationToken cancellationToken = default)
    {
        var sourceName = Path.GetFileName(sourcePath);
        if (string.IsNullOrWhiteSpace(sourceName) || !EclipseBinaryOutputName.IsMatch(sourceName))
            throw new InvalidDataException("The ECLIPSE output is not an approved binary result family.");
        return await CopyEclipseOutputAsync(outputDirectory, sourcePath, cancellationToken);
    }

    public async Task<ArtifactDescriptor> CopyEclipseFormattedSummaryOutputAsync(
        string outputDirectory, string sourcePath, CancellationToken cancellationToken = default)
    {
        if (!EclipseRunRules.IsFormattedSummaryOutput(sourcePath))
            throw new InvalidDataException("Only numeric formatted Summary data may be downloaded.");
        // Keep the source read-locked through validation and copying, not just during one pass.
        await using var guard = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        EclipseFormattedSummaryParser.ValidateDataFile(sourcePath);
        return await CopyEclipseOutputAsync(outputDirectory, sourcePath, cancellationToken);
    }

    public async Task<ArtifactDescriptor> CopyEclipseFormattedGridOutputAsync(string outputDirectory,string sourcePath,CancellationToken cancellationToken=default)
    {
        await using var guard=new FileStream(sourcePath,FileMode.Open,FileAccess.Read,FileShare.Read);
        EclipseFormattedGridOutput.Validate(sourcePath);
        return await CopyEclipseOutputAsync(outputDirectory,sourcePath,cancellationToken);
    }

    private async Task<ArtifactDescriptor> CopyEclipseOutputAsync(
        string outputDirectory, string sourcePath, CancellationToken cancellationToken)
    {
        var sourceName = Path.GetFileName(sourcePath);
        var targetName = "eclipse-output-" + sourceName;
        var targetPath = Path.Combine(outputDirectory, targetName);
        var temporaryPath = targetPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            await using (var source = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
            await using (var target = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                await source.CopyToAsync(target, cancellationToken);
            }
            File.Move(temporaryPath, targetPath, overwrite: false);
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }

        var info = new FileInfo(targetPath);
        return new ArtifactDescriptor(storage.ToStorageKey(targetPath), info.Length,
            await ComputeFileSha256Async(targetPath, cancellationToken), "application/octet-stream");
    }

    public Task<ArtifactDescriptor> WriteEclipseAppliedPackageAsync(
        string outputDirectory, EclipseDeckPackage package, CancellationToken cancellationToken = default)
        => WriteEclipseInputPackageAsync(outputDirectory, package, "eclipse-grid-field-applied-package.zip", cancellationToken);

    public Task<ArtifactDescriptor> WriteEclipseSchedulePackageAsync(
        string outputDirectory, EclipseDeckPackage package, CancellationToken cancellationToken = default)
        => WriteEclipseInputPackageAsync(outputDirectory, package, "eclipse-schedule-applied-package.zip", cancellationToken);

    public Task<ArtifactDescriptor> WriteEclipseCompositionalPackageAsync(string outputDirectory, EclipseDeckPackage package, CancellationToken cancellationToken = default)
        => WriteEclipseInputPackageAsync(outputDirectory, package, "eclipse-compositional-input-package.zip", cancellationToken);

    public Task<ArtifactDescriptor> WriteEclipseThermalPackageAsync(string outputDirectory, EclipseDeckPackage package, CancellationToken cancellationToken = default)
        => WriteEclipseInputPackageAsync(outputDirectory, package, "eclipse-thermal-input-package.zip", cancellationToken);

    public async Task<ArtifactDescriptor> WriteEclipseForecastPackageAsync(
        string outputDirectory, EclipseDeckPackage package, string restartPath, CancellationToken cancellationToken = default)
    {
        var catalog = EclipseFormattedRestartReader.Read(restartPath);
        if (catalog.Reports.Count != 1 || catalog.ArtifactSizeBytes > 64L * 1024 * 1024)
            throw new InvalidDataException("Portable forecast requires exactly one complete bounded restart report.");
        var source = EclipseForecastSourceReader.Read(package, true);
        if (source.StartDate != catalog.StartDate || source.RestartCase != catalog.CaseName || source.RestartReport != catalog.Reports[0].ReportNumber
            || source.FutureDates.Any(date => string.CompareOrdinal(date, catalog.Reports[0].Date) <= 0))
            throw new InvalidDataException("Portable restart must match the actual forecast declaration and future dates.");
        var binding = new EclipsePortableRestartBinding("eclipse-portable-restart/1", source.PhysicsSha256, catalog);
        await WriteJsonAsync(package.Root, EclipsePortableRestartBinding.Filename, binding, cancellationToken);
        var bindingPath = Path.Combine(package.Root, EclipsePortableRestartBinding.Filename);
        var portable = package with { Files = package.Files.Concat(new[] { restartPath, bindingPath }).ToArray() };
        _ = EclipsePortableRestartBinding.Validate(package);
        return await WriteEclipseInputPackageAsync(outputDirectory, portable, "eclipse-forecast-applied-package.zip", cancellationToken);
    }

    private async Task<ArtifactDescriptor> WriteEclipseInputPackageAsync(
        string outputDirectory, EclipseDeckPackage package, string artifactName, CancellationToken cancellationToken)
    {
        const long maximumBytes = 256_000_000;
        var root = Path.GetFullPath(package.Root).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var targetPath = Path.Combine(outputDirectory, artifactName);
        var temporaryPath = targetPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            long totalBytes = 0;
            var entries = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            await using (var file = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
            using (var archive = new ZipArchive(file, ZipArchiveMode.Create, leaveOpen: true))
            {
                foreach (var sourcePath in package.Files)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var fullPath = Path.GetFullPath(sourcePath);
                    var relative = Path.GetRelativePath(root, fullPath).Replace('\\', '/');
                    if (relative.StartsWith("../", StringComparison.Ordinal) || relative == ".." || Path.IsPathRooted(relative)
                        || relative.Split('/').Any(part => part is "" or "." or "..")
                        || !entries.Add(relative) || !File.Exists(fullPath)
                        || (File.GetAttributes(fullPath) & FileAttributes.ReparsePoint) != 0)
                        throw new InvalidDataException("The ECLIPSE package contains an unsafe file path.");
                    totalBytes = checked(totalBytes + new FileInfo(fullPath).Length);
                    if (totalBytes > maximumBytes) throw new InvalidDataException("The derived ECLIPSE input package exceeds the 256 MB limit.");
                    archive.CreateEntryFromFile(fullPath, relative, CompressionLevel.Optimal);
                }
            }
            if (new FileInfo(temporaryPath).Length > maximumBytes)
                throw new InvalidDataException("The derived ECLIPSE ZIP exceeds the 256 MB limit.");
            File.Move(temporaryPath, targetPath, overwrite: false);
            var info = new FileInfo(targetPath);
            return new ArtifactDescriptor(storage.ToStorageKey(targetPath), info.Length,
                await ComputeFileSha256Async(targetPath, cancellationToken), "application/zip");
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }

    public async Task<ArtifactDescriptor> CopyPipesimAppliedModelAsync(
        string outputDirectory,
        string sourcePath,
        CancellationToken cancellationToken = default,
        string artifactName = "pipesim-network-optimizer-applied.pips")
    {
        if (!File.Exists(sourcePath) || !string.Equals(Path.GetExtension(sourcePath), ".pips", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("The applied PIPESIM model copy is unavailable.");
        if (artifactName is not ("pipesim-network-optimizer-applied.pips" or "pipesim-trajectory-applied.pips" or "pipesim-well-tubing-applied.pips" or "pipesim-network-flowline-applied.pips" or "pipesim-network-connection-applied.pips"))
            throw new InvalidDataException("The applied PIPESIM artifact name is not approved.");
        var targetPath = Path.Combine(outputDirectory, artifactName);
        var temporaryPath = targetPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            await using (var source = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
            await using (var target = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                await source.CopyToAsync(target, cancellationToken);
            }
            File.Move(temporaryPath, targetPath, overwrite: false);
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }

        var info = new FileInfo(targetPath);
        return new ArtifactDescriptor(storage.ToStorageKey(targetPath), info.Length,
            await ComputeFileSha256Async(targetPath, cancellationToken), "application/octet-stream");
    }

    private async Task<ArtifactDescriptor> WriteBytesAsync(
        string outputDirectory,
        string name,
        byte[] bytes,
        string contentType,
        CancellationToken cancellationToken)
    {
        var path = Path.Combine(outputDirectory, name);
        var temporary = path + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            await File.WriteAllBytesAsync(temporary, bytes, cancellationToken);
            File.Move(temporary, path, overwrite: false);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
        var sha = Convert.ToHexStringLower(SHA256.HashData(bytes));
        return new ArtifactDescriptor(storage.ToStorageKey(path), bytes.LongLength, sha, contentType);
    }

    private static async Task<string> ComputeFileSha256Async(string path, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, cancellationToken));
    }

    private static string SanitizeLogMessage(string message)
    {
        var singleLine = SanitizeSensitiveText(message).Replace('\r', ' ').Replace('\n', ' ').Trim();
        if (singleLine.Length > 500) singleLine = singleLine[..500];
        return singleLine;
    }

    private static byte[] SerializeSanitized(JsonElement value)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
        {
            WriteSanitized(writer, value);
        }
        return stream.ToArray();
    }

    private static void WriteSanitized(Utf8JsonWriter writer, JsonElement value)
    {
        switch (value.ValueKind)
        {
            case JsonValueKind.Object:
                writer.WriteStartObject();
                var names = new HashSet<string>(StringComparer.Ordinal);
                foreach (var property in value.EnumerateObject())
                {
                    var baseName = SanitizeSensitiveText(property.Name);
                    var name = baseName;
                    for (var suffix = 2; !names.Add(name); suffix++) name = baseName + "#" + suffix;
                    writer.WritePropertyName(name);
                    if (IsSensitiveKey(property.Name)) writer.WriteStringValue(Redacted);
                    else WriteSanitized(writer, property.Value);
                }
                writer.WriteEndObject();
                break;
            case JsonValueKind.Array:
                writer.WriteStartArray();
                foreach (var item in value.EnumerateArray()) WriteSanitized(writer, item);
                writer.WriteEndArray();
                break;
            case JsonValueKind.String:
                writer.WriteStringValue(SanitizeSensitiveText(value.GetString() ?? string.Empty));
                break;
            case JsonValueKind.Undefined:
                writer.WriteNullValue();
                break;
            default:
                value.WriteTo(writer);
                break;
        }
    }

    private static string SanitizeSensitiveText(string value)
    {
        var redacted = AuthorizationCredential.Replace(value, "${prefix}" + Redacted);
        redacted = BearerCredential.Replace(redacted, "Bearer " + Redacted);
        redacted = ApiTokenCredential.Replace(redacted, "${prefix}" + Redacted);
        redacted = TokenCredential.Replace(redacted, "${prefix}" + Redacted);
        redacted = CookieCredential.Replace(redacted, "${prefix}" + Redacted);
        redacted = SensitiveKeyValueCredential.Replace(redacted, "${prefix}" + Redacted);
        return SanitizeLocationText(redacted);
    }

    private static string SanitizeLocationText(string value)
    {
        var redacted = FileUri.Replace(value, Redacted);
        redacted = LocalPipe.Replace(redacted, Redacted);
        redacted = UncPath.Replace(redacted, Redacted);
        redacted = DrivePath.Replace(redacted, Redacted);
        return UnixPath.Replace(redacted, Redacted);
    }

    private static bool IsSensitiveKey(string name)
    {
        var normalized = new string(name.Where(char.IsLetterOrDigit).ToArray()).ToLowerInvariant();
        if (normalized is "password" or "passwd" or "pwd" or "secret" or "credential" or "credentials"
            or "authorization" or "cookie" or "apikey" or "accesstoken" or "refreshtoken" or "token"
            or "license" or "licensekey" or "licensefile" or "licensetext" or "connectionstring" or "privatekey") return true;

        string? previousComponent = null;
        for (var index = 0; index < name.Length;)
        {
            while (index < name.Length && !char.IsLetterOrDigit(name[index])) index++;
            var start = index;
            while (index < name.Length && char.IsLetterOrDigit(name[index]))
            {
                if (index > start && char.IsUpper(name[index]) &&
                    (char.IsLower(name[index - 1]) ||
                     (char.IsUpper(name[index - 1]) && index + 1 < name.Length && char.IsLower(name[index + 1])))) break;
                index++;
            }

            if (index > start)
            {
                var component = name[start..index];
                if (IsCompoundSensitiveKeyComponent(component) ||
                    previousComponent?.Equals("api", StringComparison.OrdinalIgnoreCase) == true &&
                    component.Equals("key", StringComparison.OrdinalIgnoreCase) ||
                    previousComponent?.Equals("private", StringComparison.OrdinalIgnoreCase) == true &&
                    component.Equals("key", StringComparison.OrdinalIgnoreCase) ||
                    previousComponent?.Equals("connection", StringComparison.OrdinalIgnoreCase) == true &&
                    component.Equals("string", StringComparison.OrdinalIgnoreCase)) return true;
                previousComponent = component;
            }
        }

        return false;
    }

    private static bool IsCompoundSensitiveKeyComponent(string component) =>
        component.Equals("password", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("passwd", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("pwd", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("secret", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("credential", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("credentials", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("authorization", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("cookie", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("token", StringComparison.OrdinalIgnoreCase) ||
        component.Equals("license", StringComparison.OrdinalIgnoreCase);
}

public sealed record EclipseOutputMetadata(string Filename, long SizeBytes, string Sha256);
