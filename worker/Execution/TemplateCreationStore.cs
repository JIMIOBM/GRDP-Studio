using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Storage;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public sealed record TemplateCreationArtifact(string Name, long SizeBytes, string Sha256);
public sealed record TemplateCreationRecord(Guid RequestId, string Fingerprint, string Status,
    DateTimeOffset UpdatedAtUtc, JsonElement? Result = null, WorkerError? Error = null, TemplateCreationArtifact? Model = null,
    string? GenerationId = null);

/// <summary>Creation records are separate from existing model Run IDs and never overwrite uploaded versions.</summary>
public sealed class TemplateCreationStore(StorageResolver storage, WorkerIdentity? identity = null)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly string generation = identity?.GenerationId ?? Guid.NewGuid().ToString("N");

    public string DirectoryFor(Guid id)
    {
        if (id == Guid.Empty) throw new ArgumentException("Creation ID is required.");
        var path = Path.Combine(storage.Root, "model-creations", id.ToString("N"));
        var current = new DirectoryInfo(path);
        while (current is not null)
        {
            if (current.Exists && (current.Attributes & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Creation storage cannot contain reparse points.");
            current = current.Parent;
        }
        return path;
    }

    public TemplateCreationRecord? Read(Guid id)
    {
        var path = Path.Combine(DirectoryFor(id), "record.json");
        RejectLinkedFile(path);
        var record = File.Exists(path) ? JsonSerializer.Deserialize<TemplateCreationRecord>(File.ReadAllText(path), Json) : null;
        if (record is not null && record.RequestId != id) throw new IOException("Creation record identity mismatch.");
        if (record?.Status == "PREPARING" && record.GenerationId != generation)
        {
            record = record with { Status = "INTERRUPTED", UpdatedAtUtc = DateTimeOffset.UtcNow,
                Error = new("EXECUTION", "CREATION_INTERRUPTED", "The creation belongs to an earlier Worker generation; it will not resume automatically.", false) };
            Save(record);
        }
        return record;
    }

    public void Claim(TemplateCreationRequest request, string fingerprint)
    {
        var directory = DirectoryFor(request.RequestId);
        Directory.CreateDirectory(directory);
        using (var stream = new FileStream(Path.Combine(directory, "claim.json"), FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, request, Json);
            stream.Flush(flushToDisk: true);
        }
        Save(new(request.RequestId, fingerprint, "PREPARING", DateTimeOffset.UtcNow, GenerationId: generation));
    }

    public void Save(TemplateCreationRecord record)
    {
        record = record with { GenerationId = record.GenerationId ?? generation };
        var directory = DirectoryFor(record.RequestId);
        var path = Path.Combine(directory, "record.json");
        RejectLinkedFile(path);
        var temporary = Path.Combine(directory, $"record-{Guid.NewGuid():N}.tmp");
        using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, record, Json);
            stream.Flush(flushToDisk: true);
        }
        File.Move(temporary, path, overwrite: true);
    }

    public string ModelPath(Guid id)
    {
        var path = Path.Combine(DirectoryFor(id), "created.pips");
        RejectLinkedFile(path);
        return path;
    }

    private static void RejectLinkedFile(string path)
    {
        if ((File.Exists(path) || Directory.Exists(path)) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new IOException("Creation file cannot be a reparse point.");
    }
}
