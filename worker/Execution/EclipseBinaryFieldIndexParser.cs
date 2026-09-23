using System.Buffers.Binary;
using System.Text;

namespace Grdp.SoftwareIntegration.Worker.Execution;

/// <summary>
/// Builds a bounded index of ECLIPSE grid/state keywords without loading field values.
/// Offsets point only into the isolated binary output and are later used by a range
/// reader; no local path or binary payload is copied into the result JSON.
/// </summary>
public static class EclipseBinaryFieldIndexParser
{
    private const int MaxRecordBytes = 512 * 1024 * 1024;
    private const int MaxSections = 8192;
    private const int MaxSegments = 65536;

    public static EclipseBinaryFieldIndex? TryParse(string path)
    {
        try
        {
            var fileInfo = new FileInfo(path);
            if (!fileInfo.Exists || fileInfo.Length < 8) return null;
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024,
                FileOptions.SequentialScan);
            var first = ReadInt32(stream, true);
            stream.Position = 0;
            var bigEndian = IsValidLength(first, stream.Length);
            var littleCandidate = ReadInt32(stream, false);
            stream.Position = 0;
            var littleEndian = IsValidLength(littleCandidate, stream.Length);
            if (bigEndian == littleEndian) return null;
            var endian = bigEndian;
            var sections = new List<EclipseBinaryFieldSection>();
            var segmentCount = 0;
            int? currentTimeStep = null;
            while (stream.Position < stream.Length)
            {
                if (sections.Count >= MaxSections) return null;
                var header = ReadRecord(stream, endian);
                if (header is null || header.Value.Data.Length != 16) return null;
                var keyword = Encoding.ASCII.GetString(header.Value.Data, 0, 8).TrimEnd('\0', ' ');
                var count = ReadInt32(header.Value.Data, 8, endian);
                var dataType = Encoding.ASCII.GetString(header.Value.Data, 12, 4).TrimEnd('\0', ' ');
                if (count < 0) return null;
                // ECLIPSE UNRST uses zero-length control records such as STARTSOL/ENDSOL
                // with the MESS type (and may also contain an empty keyword marker). They
                // carry no field values, so they must not invalidate the rest of the file.
                if (count == 0) continue;
                if (!TryElementSize(dataType, out var elementSize)) return null;
                var totalBytes = checked((long)count * elementSize);
                var segments = new List<EclipseBinaryDataSegment>();
                var consumed = 0L;
                int? sequenceNumber = null;
                while (consumed < totalBytes)
                {
                    var data = ReadDataRecord(stream, endian, totalBytes - consumed,
                        keyword.Equals("SEQNUM", StringComparison.OrdinalIgnoreCase) && consumed == 0);
                    if (data is null) return null;
                    var parsed = data.Value;
                    if (parsed.Segment.Length % elementSize != 0) return null;
                    if (++segmentCount > MaxSegments) return null;
                    sequenceNumber ??= parsed.FirstInt32;
                    segments.Add(parsed.Segment);
                    consumed += parsed.Segment.Length;
                }
                if (keyword.Equals("SEQNUM", StringComparison.OrdinalIgnoreCase) && sequenceNumber.HasValue && sequenceNumber.Value > 0)
                    currentTimeStep = sequenceNumber.Value;
                if (IsSafeKeyword(keyword))
                {
                    sections.Add(new EclipseBinaryFieldSection(keyword, dataType, count, elementSize,
                        totalBytes, segments, currentTimeStep));
                }
            }
            return new EclipseBinaryFieldIndex(Path.GetFileName(path), fileInfo.Length,
                endian ? "BIG" : "LITTLE", sections);
        }
        catch (Exception exception) when (exception is IOException or EndOfStreamException or InvalidDataException or OverflowException)
        {
            return null;
        }
    }

    private static bool TryElementSize(string dataType, out int size)
    {
        size = dataType switch
        {
            "INTE" or "LOGI" or "REAL" => 4,
            "DOUB" => 8,
            "CHAR" => 8,
            _ => 0
        };
        return size != 0;
    }

    private static bool IsSafeKeyword(string value) => value.Length is > 0 and <= 8 &&
        value.All(character => character is >= 'A' and <= 'Z' or >= '0' and <= '9' or '_');

    private static BinaryRecord? ReadRecord(Stream stream, bool bigEndian)
    {
        var length = ReadInt32(stream, bigEndian);
        if (length < 8 || length > MaxRecordBytes || stream.Position + length + 4 > stream.Length)
            throw new InvalidDataException("Invalid ECLIPSE binary record length.");
        var data = new byte[length];
        ReadExactly(stream, data);
        var footer = ReadInt32(stream, bigEndian);
        if (footer != length) throw new InvalidDataException("Invalid ECLIPSE binary record footer.");
        return new BinaryRecord(data);
    }

    private static ParsedDataRecord? ReadDataRecord(Stream stream, bool bigEndian, long remaining, bool captureFirstInt32)
    {
        var length = ReadInt32(stream, bigEndian);
        if (length <= 0 || length > MaxRecordBytes || length > remaining || stream.Position + length + 4 > stream.Length)
            throw new InvalidDataException("Invalid ECLIPSE binary data record length.");
        var offset = stream.Position;
        int? firstInt32 = null;
        if (captureFirstInt32 && length >= 4)
        {
            Span<byte> firstBytes = stackalloc byte[4];
            ReadExactly(stream, firstBytes);
            firstInt32 = ReadInt32(firstBytes, bigEndian);
            stream.Position = offset;
        }
        stream.Seek(length, SeekOrigin.Current);
        var footer = ReadInt32(stream, bigEndian);
        if (footer != length) throw new InvalidDataException("Invalid ECLIPSE binary data record footer.");
        return new ParsedDataRecord(new EclipseBinaryDataSegment(offset, length), firstInt32);
    }

    private static bool IsValidLength(int length, long totalLength) =>
        length >= 8 && length <= MaxRecordBytes && length + 8 <= totalLength;

    private static int ReadInt32(Stream stream, bool bigEndian)
    {
        Span<byte> bytes = stackalloc byte[4];
        ReadExactly(stream, bytes);
        var value = BinaryPrimitives.ReadInt32LittleEndian(bytes);
        return BitConverter.IsLittleEndian == bigEndian ? BinaryPrimitives.ReverseEndianness(value) : value;
    }

    private static int ReadInt32(byte[] bytes, int offset, bool bigEndian)
    {
        var value = BitConverter.ToInt32(bytes, offset);
        return BitConverter.IsLittleEndian == bigEndian ? BinaryPrimitives.ReverseEndianness(value) : value;
    }

    private static int ReadInt32(ReadOnlySpan<byte> bytes, bool bigEndian)
    {
        var value = BinaryPrimitives.ReadInt32LittleEndian(bytes);
        return BitConverter.IsLittleEndian == bigEndian ? BinaryPrimitives.ReverseEndianness(value) : value;
    }

    private static void ReadExactly(Stream stream, Span<byte> buffer)
    {
        while (!buffer.IsEmpty)
        {
            var read = stream.Read(buffer);
            if (read == 0) throw new EndOfStreamException();
            buffer = buffer[read..];
        }
    }

    private static void ReadExactly(Stream stream, byte[] buffer) => ReadExactly(stream, buffer.AsSpan());

    private readonly record struct BinaryRecord(byte[] Data);
    private readonly record struct ParsedDataRecord(EclipseBinaryDataSegment Segment, int? FirstInt32);
}

public sealed record EclipseBinaryFieldIndex(
    string FileName,
    long FileSize,
    string ByteOrder,
    IReadOnlyList<EclipseBinaryFieldSection> Sections);

public sealed record EclipseBinaryFieldSection(
    string Keyword,
    string DataType,
    int Count,
    int ElementSize,
    long DataBytes,
    IReadOnlyList<EclipseBinaryDataSegment> Segments,
    int? TimeStep);

public sealed record EclipseBinaryDataSegment(long Offset, int Length);
