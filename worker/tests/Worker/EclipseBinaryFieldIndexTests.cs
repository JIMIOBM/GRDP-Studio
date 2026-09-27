using System.Buffers.Binary;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseBinaryFieldIndexTests
{
    [Fact]
    public void IndexesKeywordCountsTypesAndDataSegmentsWithoutMaterializingValues()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-field-index-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var path = Path.Combine(root, "CASE.INIT");
            File.WriteAllBytes(path, KeywordSection("GRIDHEAD", "INTE", Ints(1, 2, 3, 4))
                .Concat(KeywordSection("PRESSURE", "REAL", Floats(100, 200, 300))).ToArray());

            var index = EclipseBinaryFieldIndexParser.TryParse(path);

            Assert.NotNull(index);
            Assert.Equal("CASE.INIT", index!.FileName);
            Assert.Equal("LITTLE", index.ByteOrder);
            Assert.Equal(new[] { "GRIDHEAD", "PRESSURE" }, index.Sections.Select(section => section.Keyword));
            var pressure = index.Sections.Single(section => section.Keyword == "PRESSURE");
            Assert.Equal("REAL", pressure.DataType);
            Assert.Equal(3, pressure.Count);
            Assert.Equal(4, pressure.ElementSize);
            Assert.Equal(12, pressure.DataBytes);
            Assert.Single(pressure.Segments);
            Assert.Equal(12, pressure.Segments[0].Length);
            Assert.True(pressure.Segments[0].Offset > 0);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void RejectsMalformedBinaryRecordFooter()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-field-index-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var path = Path.Combine(root, "CASE.UNRST");
            var bytes = KeywordSection("PRESSURE", "REAL", Floats(100));
            BinaryPrimitives.WriteInt32LittleEndian(bytes.AsSpan(bytes.Length - 4), 99);
            File.WriteAllBytes(path, bytes);

            Assert.Null(EclipseBinaryFieldIndexParser.TryParse(path));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void BindsUnrstFieldsToTheRealSeqnumTimeStep()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-field-index-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var path = Path.Combine(root, "CASE.UNRST");
            File.WriteAllBytes(path, KeywordSection("SEQNUM", "INTE", Ints(7))
                .Concat(KeywordSection("PRESSURE", "REAL", Floats(100)))
                .Concat(KeywordSection("SEQNUM", "INTE", Ints(8)))
                .Concat(KeywordSection("PRESSURE", "REAL", Floats(200))).ToArray());

            var index = EclipseBinaryFieldIndexParser.TryParse(path);

            Assert.NotNull(index);
            Assert.Equal(new int?[] { 7, 8 }, index!.Sections.Where(section => section.Keyword == "PRESSURE")
                .Select(section => section.TimeStep).ToArray());
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void SkipsZeroLengthMessageRecordsAndContinuesIndexingUnrst()
    {
        var root = Path.Combine(Path.GetTempPath(), "grdp-eclipse-field-index-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var path = Path.Combine(root, "CASE.UNRST");
            File.WriteAllBytes(path, KeywordSection("SEQNUM", "INTE", Ints(7))
                .Concat(HeaderOnly("STARTSOL", "MESS"))
                .Concat(KeywordSection("PRESSURE", "REAL", Floats(100)))
                .Concat(HeaderOnly("ENDSOL", "MESS"))
                .Concat(HeaderOnly(string.Empty, string.Empty)).ToArray());

            var index = EclipseBinaryFieldIndexParser.TryParse(path);

            Assert.NotNull(index);
            var pressure = Assert.Single(index!.Sections, section => section.Keyword == "PRESSURE");
            Assert.Equal(7, pressure.TimeStep);
        }
        finally { Directory.Delete(root, true); }
    }

    private static byte[] KeywordSection(string keyword, string dataType, byte[] values)
    {
        var elementSize = dataType == "DOUB" ? 8 : dataType == "CHAR" ? 8 : 4;
        var header = Header(keyword, dataType, values.Length / elementSize);
        return Record(header).Concat(values.Length == 0 ? [] : Record(values)).ToArray();
    }

    private static byte[] HeaderOnly(string keyword, string dataType) => Record(Header(keyword, dataType, 0));

    private static byte[] Header(string keyword, string dataType, int count) => Encoding.ASCII.GetBytes(keyword.PadRight(8)[..8])
        .Concat(Int32(count))
        .Concat(Encoding.ASCII.GetBytes(dataType.PadRight(4)[..4])).ToArray();

    private static byte[] Int32(int value)
    {
        var bytes = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(bytes, value);
        return bytes;
    }

    private static byte[] Ints(params int[] values) => values.SelectMany(Int32).ToArray();
    private static byte[] Floats(params float[] values) => values.SelectMany(value =>
    {
        var bytes = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(bytes, BitConverter.SingleToInt32Bits(value));
        return bytes;
    }).ToArray();

    private static byte[] Record(byte[] data)
    {
        var result = new byte[data.Length + 8];
        BinaryPrimitives.WriteInt32LittleEndian(result.AsSpan(0, 4), data.Length);
        data.CopyTo(result, 4);
        BinaryPrimitives.WriteInt32LittleEndian(result.AsSpan(data.Length + 4), data.Length);
        return result;
    }
}
