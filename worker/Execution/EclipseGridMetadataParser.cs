using System.Buffers.Binary;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>
/// Reads only the bounded metadata needed to index an ECLIPSE EGRID output.
/// It deliberately does not materialize cell geometry or field values in the Run JSON.
/// </summary>
public static class EclipseGridMetadataParser
{
    private const int MaxRecordBytes = 512 * 1024 * 1024;

    public static EclipseGridMetadata? TryParse(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024,
                FileOptions.SequentialScan);
            using var reader = new BinaryReader(stream, Encoding.ASCII, leaveOpen: true);
            var first = reader.ReadBytes(4);
            if (first.Length != 4) return null;
            var bigEndian = ReadInt32(first, 0, true) == 16;
            var littleEndian = ReadInt32(first, 0, false) == 16;
            if (!bigEndian && !littleEndian) return null;

            stream.Position = 0;
            var nx = 0;
            var ny = 0;
            var nz = 0;
            var activeCells = (int?)null;
            while (stream.Position < stream.Length)
            {
                var header = ReadRecord(reader, bigEndian);
                if (header is null || header.Data.Length < 16) return null;
                var keyword = Encoding.ASCII.GetString(header.Data, 0, 8).TrimEnd('\0', ' ');
                var count = ReadInt32(header.Data, 8, bigEndian);
                var dtype = Encoding.ASCII.GetString(header.Data, 12, 4).TrimEnd('\0', ' ');
                if (count < 0) return null;

                var elementSize = dtype switch
                {
                    "INTE" or "LOGI" or "REAL" => 4,
                    "DOUB" => 8,
                    "CHAR" => 8,
                    _ => 4
                };
                var totalBytes = checked((long)count * elementSize);
                if (totalBytes > MaxRecordBytes && keyword is "GRIDHEAD" or "ACTNUM") return null;

                var gridHeadPrefix = keyword.Equals("GRIDHEAD", StringComparison.OrdinalIgnoreCase)
                    ? new byte[Math.Min(16, (int)Math.Min(totalBytes, 16))]
                    : null;
                var actnumPositive = keyword.Equals("ACTNUM", StringComparison.OrdinalIgnoreCase) && dtype == "INTE"
                    ? 0
                    : (int?)null;
                var consumed = 0L;
                var prefixOffset = 0;
                while (consumed < totalBytes)
                {
                    var dataRecord = ReadRecord(reader, bigEndian);
                    if (dataRecord is null) return null;
                    var data = dataRecord.Data;
                    if (data.Length > totalBytes - consumed) return null;
                    if (gridHeadPrefix is not null && prefixOffset < gridHeadPrefix.Length)
                    {
                        var copy = Math.Min(gridHeadPrefix.Length - prefixOffset, data.Length);
                        data.AsSpan(0, copy).CopyTo(gridHeadPrefix.AsSpan(prefixOffset));
                        prefixOffset += copy;
                    }
                    if (actnumPositive.HasValue)
                    {
                        if (data.Length % 4 != 0) return null;
                        for (var offset = 0; offset < data.Length; offset += 4)
                            if (ReadInt32(data, offset, bigEndian) > 0) actnumPositive++;
                    }
                    consumed += data.Length;
                }

                if (gridHeadPrefix is { Length: >= 16 } && dtype == "INTE")
                {
                    nx = ReadInt32(gridHeadPrefix, 4, bigEndian);
                    ny = ReadInt32(gridHeadPrefix, 8, bigEndian);
                    nz = ReadInt32(gridHeadPrefix, 12, bigEndian);
                }
                if (actnumPositive.HasValue) activeCells = actnumPositive.Value;
                if (keyword.Equals("ENDFILE", StringComparison.OrdinalIgnoreCase)) break;
            }

            return nx > 0 && ny > 0 && nz > 0
                ? new EclipseGridMetadata(Path.GetFileName(path), nx, ny, nz, activeCells)
                : null;
        }
        catch (Exception exception) when (exception is IOException or EndOfStreamException or InvalidDataException or OverflowException)
        {
            return null;
        }
    }

    private static BinaryRecord? ReadRecord(BinaryReader reader, bool bigEndian)
    {
        var lengthBytes = reader.ReadBytes(4);
        if (lengthBytes.Length == 0) return null;
        if (lengthBytes.Length != 4) throw new EndOfStreamException();
        var length = ReadInt32(lengthBytes, 0, bigEndian);
        if (length < 0 || length > MaxRecordBytes) throw new InvalidDataException("Invalid ECLIPSE binary record length.");
        var data = reader.ReadBytes(length);
        if (data.Length != length) throw new EndOfStreamException();
        var footer = reader.ReadBytes(4);
        if (footer.Length != 4 || ReadInt32(footer, 0, bigEndian) != length)
            throw new InvalidDataException("Invalid ECLIPSE binary record footer.");
        return new BinaryRecord(data);
    }

    private static int ReadInt32(byte[] data, int offset, bool bigEndian)
    {
        var value = BitConverter.ToInt32(data, offset);
        return BitConverter.IsLittleEndian == bigEndian ? BinaryPrimitives.ReverseEndianness(value) : value;
    }

    private sealed record BinaryRecord(byte[] Data);
}

public sealed record EclipseGridMetadata(string FileName, int Nx, int Ny, int Nz, int? ActiveCells);
