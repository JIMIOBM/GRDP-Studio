using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

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

    // ECLIPSE text and binary outputs can expose paths, host details, and license diagnostics.
    // Publish only their fresh-file metadata; normalized results retain the supported summary data.
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
