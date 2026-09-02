using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Storage;

public sealed class ArtifactStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };
    private readonly StorageResolver storage;

    public ArtifactStore(StorageResolver storage) => this.storage = storage;

    public async Task<ArtifactDescriptor> WriteJsonAsync(string outputDirectory, string name, object value, CancellationToken cancellationToken = default)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);
        return await WriteBytesAsync(outputDirectory, name, bytes, "application/json", cancellationToken);
    }

    public async Task<ArtifactDescriptor> WriteJsonElementAsync(string outputDirectory, string name, JsonElement value, CancellationToken cancellationToken = default)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);
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

    private static string SanitizeLogMessage(string message)
    {
        var singleLine = message.Replace('\r', ' ').Replace('\n', ' ').Trim();
        if (singleLine.Length > 500) singleLine = singleLine[..500];
        return singleLine;
    }
}
