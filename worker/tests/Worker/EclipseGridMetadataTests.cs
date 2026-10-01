using System.Buffers.Binary;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseGridMetadataTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-grid-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void ReadsDimensionsAndActiveCellCountWithoutMaterializingFields()
    {
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "CASE.EGRID");
        File.WriteAllBytes(path, Sections(
            Section("GRIDHEAD", "INTE", Integers(1, 2, 3, 4)),
            Section("ACTNUM", "INTE", Integers(1, 0, 1, 1, 0, 1)),
            Section("ENDFILE", "INTE", [])));

        var metadata = EclipseGridMetadataParser.TryParse(path);

        Assert.Equal(new EclipseGridMetadata("CASE.EGRID", 2, 3, 4, 4), metadata);
    }

    [Fact]
    public void RejectsInvalidRecordFooter()
    {
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "BROKEN.EGRID");
        File.WriteAllBytes(path, [0, 0, 0, 16, .. Encoding.ASCII.GetBytes("GRIDHEAD"), .. new byte[8], 0, 0, 0, 15]);

        Assert.Null(EclipseGridMetadataParser.TryParse(path));
    }

    private static byte[] Sections(params byte[][] sections) => sections.SelectMany(value => value).ToArray();

    private static byte[] Section(string keyword, string dtype, byte[] values)
    {
        var header = new byte[16];
        Encoding.ASCII.GetBytes(keyword.PadRight(8)).CopyTo(header, 0);
        WriteInt32(header, 8, values.Length / (dtype == "DOUB" ? 8 : 4));
        Encoding.ASCII.GetBytes(dtype.PadRight(4)).CopyTo(header, 12);
        return Record(header).Concat(Records(values)).ToArray();
    }

    private static byte[] Records(byte[] values) => Record(values);

    private static byte[] Record(byte[] value)
    {
        var length = BitConverter.GetBytes(value.Length).Reverse().ToArray();
        return length.Concat(value).Concat(length).ToArray();
    }

    private static byte[] Integers(params int[] values)
    {
        var bytes = new byte[values.Length * 4];
        for (var index = 0; index < values.Length; index++) WriteInt32(bytes, index * 4, values[index]);
        return bytes;
    }

    private static void WriteInt32(byte[] buffer, int offset, int value) =>
        BinaryPrimitives.WriteInt32BigEndian(buffer.AsSpan(offset, 4), value);

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }
}
