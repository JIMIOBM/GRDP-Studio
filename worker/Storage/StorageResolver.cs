using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Storage;

public sealed class StorageException : Exception
{
    public StorageException(string code, string message, int httpStatus = StatusCodes.Status400BadRequest) : base(message)
    {
        Code = code;
        HttpStatus = httpStatus;
    }

    public string Code { get; }
    public int HttpStatus { get; }
}

public sealed record RunDirectories(string Root, string Input, string Work, string Output, string ModelCopy);

public sealed class StorageResolver
{
    private readonly string root;
    private readonly string rootPrefix;

    public StorageResolver(IOptions<WorkerOptions> options)
    {
        root = Path.GetFullPath(options.Value.StorageRoot);
        rootPrefix = root.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
    }

    public string Root => root;

    public string ResolveExistingModel(string? storageKey)
    {
        if (string.IsNullOrWhiteSpace(storageKey))
        {
            throw new StorageException("MODEL_STORAGE_KEY_REQUIRED", "modelStorageKey is required.");
        }

        var key = storageKey.Trim();
        if (Path.IsPathFullyQualified(key) || Path.IsPathRooted(key) || key.Contains(':') || key.Split(['/', '\\']).Any(segment => segment == ".."))
        {
            throw new StorageException("INVALID_STORAGE_KEY", "modelStorageKey must be a relative key without parent traversal.");
        }

        string fullPath;
        try
        {
            fullPath = Path.GetFullPath(Path.Combine(root, key));
        }
        catch (Exception exception) when (exception is ArgumentException or NotSupportedException or PathTooLongException)
        {
            throw new StorageException("INVALID_STORAGE_KEY", "modelStorageKey is invalid.");
        }

        if (!fullPath.StartsWith(rootPrefix, StringComparison.OrdinalIgnoreCase) || string.Equals(fullPath, root, StringComparison.OrdinalIgnoreCase))
        {
            throw new StorageException("STORAGE_ROOT_ESCAPE", "modelStorageKey resolves outside the configured storage root.");
        }

        RejectReparsePoints(fullPath);
        if (!File.Exists(fullPath))
        {
            throw new StorageException("MODEL_NOT_FOUND", "The model file does not exist.", StatusCodes.Status404NotFound);
        }

        if (!string.Equals(Path.GetExtension(fullPath), ".pips", StringComparison.OrdinalIgnoreCase))
        {
            throw new StorageException("UNSUPPORTED_MODEL_FILE", "The run adapter supports only .pips model files.");
        }

        return fullPath;
    }

    public async Task<string> ComputeSha256Async(string path, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        var hash = await SHA256.HashDataAsync(stream, cancellationToken);
        return Convert.ToHexStringLower(hash);
    }

    public RunDirectories CreateRunDirectories(long runId, string sourceModel)
    {
        var runRoot = Path.Combine(root, "jobs", runId.ToString(System.Globalization.CultureInfo.InvariantCulture));
        EnsureRunDirectoryAvailable(runId);
        var input = Path.Combine(runRoot, "input");
        var work = Path.Combine(runRoot, "work");
        var output = Path.Combine(runRoot, "output");
        Directory.CreateDirectory(input);
        Directory.CreateDirectory(work);
        Directory.CreateDirectory(output);
        var modelCopy = Path.Combine(input, Path.GetFileName(sourceModel));
        File.Copy(sourceModel, modelCopy, overwrite: false);
        CopyCompanionDirectory(sourceModel, modelCopy);
        return new RunDirectories(runRoot, input, work, output, modelCopy);
    }

    public void EnsureRunDirectoryAvailable(long runId)
    {
        var runRoot = Path.Combine(root, "jobs", runId.ToString(System.Globalization.CultureInfo.InvariantCulture));
        if (Directory.Exists(runRoot) || File.Exists(runRoot))
        {
            throw new StorageException("JOB_DIRECTORY_EXISTS", "The run job directory already exists.", StatusCodes.Status409Conflict);
        }
    }

    public RunDirectories CreateValidationDirectories(string sourceModel)
    {
        var runRoot = Path.Combine(root, "validation", Guid.NewGuid().ToString("N"));
        var input = Path.Combine(runRoot, "input");
        var work = Path.Combine(runRoot, "work");
        var output = Path.Combine(runRoot, "output");
        Directory.CreateDirectory(input);
        Directory.CreateDirectory(work);
        Directory.CreateDirectory(output);
        var modelCopy = Path.Combine(input, Path.GetFileName(sourceModel));
        File.Copy(sourceModel, modelCopy, overwrite: false);
        CopyCompanionDirectory(sourceModel, modelCopy);
        return new RunDirectories(runRoot, input, work, output, modelCopy);
    }

    public string ToStorageKey(string path)
    {
        var fullPath = Path.GetFullPath(path);
        if (!fullPath.StartsWith(rootPrefix, StringComparison.OrdinalIgnoreCase))
        {
            throw new StorageException("ARTIFACT_ROOT_ESCAPE", "Artifact path resolves outside the configured storage root.");
        }

        return Path.GetRelativePath(root, fullPath).Replace('\\', '/');
    }

    public static bool TryDeleteDirectory(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return true;
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
            return !Directory.Exists(path) && !File.Exists(path);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private void RejectReparsePoints(string fullPath)
    {
        var relative = Path.GetRelativePath(root, fullPath);
        var current = root;
        if (Directory.Exists(root) && (File.GetAttributes(root) & FileAttributes.ReparsePoint) != 0)
        {
            throw new StorageException("REPARSE_POINT_REJECTED", "Storage keys may not traverse reparse points.");
        }

        foreach (var segment in relative.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
        {
            current = Path.Combine(current, segment);
            if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
            {
                throw new StorageException("REPARSE_POINT_REJECTED", "Storage keys may not traverse reparse points.");
            }
        }
    }

    private static void CopyCompanionDirectory(string sourceModel, string targetModel)
    {
        var sourceCompanion = Path.ChangeExtension(sourceModel, ".pipr");
        if (!Directory.Exists(sourceCompanion)) return;
        var targetCompanion = Path.ChangeExtension(targetModel, ".pipr");
        CopyDirectoryWithoutReparsePoints(sourceCompanion, targetCompanion);
    }

    private static void CopyDirectoryWithoutReparsePoints(string source, string target)
    {
        if ((File.GetAttributes(source) & FileAttributes.ReparsePoint) != 0)
        {
            throw new StorageException("REPARSE_POINT_REJECTED", "Model companion directories may not contain reparse points.");
        }

        Directory.CreateDirectory(target);
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.TopDirectoryOnly))
        {
            if ((File.GetAttributes(file) & FileAttributes.ReparsePoint) != 0)
            {
                throw new StorageException("REPARSE_POINT_REJECTED", "Model companion directories may not contain reparse points.");
            }

            File.Copy(file, Path.Combine(target, Path.GetFileName(file)), overwrite: false);
        }

        foreach (var directory in Directory.EnumerateDirectories(source, "*", SearchOption.TopDirectoryOnly))
        {
            CopyDirectoryWithoutReparsePoints(directory, Path.Combine(target, Path.GetFileName(directory)));
        }
    }
}
