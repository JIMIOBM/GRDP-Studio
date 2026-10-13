using System.Buffers.Binary;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseGridRegionEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package, EclipseGridRegionParameters p,
        string initPath, string gridPath, CancellationToken cancellationToken)
    {
        ValidateBaseline(p, initPath, gridPath);
        if (package.Files.Count != 1 || !string.Equals(package.Files[0], package.MainFile, StringComparison.OrdinalIgnoreCase))
            throw Error("PACKAGE", "Region editing requires one DATA without INCLUDE.");
        var text = EclipseDeckPackageResolver.ReadUtf8(package.MainFile);
        var lines = text.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        int[] Find(string key) => lines.Select((line, index) => (line, index))
            .Where(item => Regex.IsMatch(item.line.Split("--", 2)[0].Trim(), "^" + key + @"(?:\s+=+)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
            .Select(item => item.index).ToArray();
        var grid = Find("GRID"); var props = Find("PROPS"); var field = Find(p.Keyword);
        if (grid.Length != 1 || props.Length != 1 || props[0] <= grid[0] || field.Length != 1 || field[0] <= grid[0] || field[0] >= props[0])
            throw Error("SECTION", "The global GRID must define the selected static array exactly once before PROPS.");
        if (new[] { "BOX", "ENDBOX", "LGR", "ENDLGR", "MULTIPLY", "INCLUDE", "EDIT", "COPY", "EQUALS", "ADD", "OPERATE", "OPERATER" }.Any(key => Find(key).Length != 0))
            throw Error("COMPLEX_GRID", "Existing box/local-grid/array editing operations require a separate contract.");
        var newline = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
        var block = string.Join(newline, "-- GRDP controlled grid-region multiplier; generated in the isolated run copy.", "MULTIPLY",
            string.Create(CultureInfo.InvariantCulture, $" {p.Keyword} {p.Multiplier:G17} {p.I1} {p.I2} {p.J1} {p.J2} {p.K1} {p.K2} /"), "/", "");
        lines[props[0]] = block + lines[props[0]];
        await File.WriteAllTextAsync(package.MainFile, string.Join(newline, lines), new UTF8Encoding(false), cancellationToken);
        return string.Create(CultureInfo.InvariantCulture, $"Applied {p.Keyword} multiplier {p.Multiplier:G6} to {p.OriginalValues.Length} active cells in I={p.I1}-{p.I2}, J={p.J1}-{p.J2}, K={p.K1}-{p.K2}.");
    }

    internal static void ValidateBaseline(EclipseGridRegionParameters p, string initPath, string gridPath)
    {
        var init = EclipseBinaryFieldIndexParser.TryParse(initPath); var grid = EclipseBinaryFieldIndexParser.TryParse(gridPath);
        var metadata = EclipseGridMetadataParser.TryParse(gridPath);
        if (init is null || grid is null || metadata is null || metadata.Nx != p.Nx || metadata.Ny != p.Ny || metadata.Nz != p.Nz
            || !string.Equals(init.FileName, p.FieldFile, StringComparison.OrdinalIgnoreCase) || !string.Equals(grid.FileName, p.GridFile, StringComparison.OrdinalIgnoreCase))
            throw Error("ARTIFACT", "Bound INIT and EGRID do not match the requested files or grid dimensions.");
        var count = (long)p.Nx * p.Ny * p.Nz;
        var fields = init.Sections.Where(s => s.Keyword == p.Keyword && s.Count == count && s.TimeStep is null && s.DataType is "REAL" or "DOUB").ToArray();
        var masks = grid.Sections.Where(s => s.Keyword == "ACTNUM" && s.Count == count && s.TimeStep is null && s.DataType == "INTE").ToArray();
        if (fields.Length != 1 || masks.Length != 1) throw Error("FIELD", "Exactly one full-grid static field and ACTNUM are required.");
        using var values = File.OpenRead(initPath); using var mask = File.OpenRead(gridPath);
        var cursor = 0;
        for (var k = p.K1; k <= p.K2; k++) for (var j = p.J1; j <= p.J2; j++) for (var i = p.I1; i <= p.I2; i++)
        {
            var index = ((long)k - 1) * p.Nx * p.Ny + ((long)j - 1) * p.Nx + i - 1;
            if (Read(mask, grid.ByteOrder, masks[0], index) <= 0) throw Error("INACTIVE_CELL", "The selected region contains an inactive cell.");
            var actual = Read(values, init.ByteOrder, fields[0], index); var original = p.OriginalValues[cursor++];
            if (!EclipseGridRegionParameters.ValidPhysical(p.Keyword, actual, p.Multiplier)
                || Math.Abs(actual - original) > Math.Max(1e-6, Math.Abs(original) * 1e-6))
                throw Error("STALE_VALUE", "Selected values no longer match the bound INIT or violate physical limits.");
        }
    }

    private static double Read(FileStream stream, string order, EclipseBinaryFieldSection field, long index)
    {
        Span<byte> data = stackalloc byte[8]; var remaining = index;
        foreach (var segment in field.Segments)
        {
            var count = segment.Length / field.ElementSize;
            if (remaining >= count) { remaining -= count; continue; }
            stream.Position = segment.Offset + remaining * field.ElementSize;
            stream.ReadExactly(data[..field.ElementSize]); var big = order == "BIG";
            if (field.DataType == "DOUB") return BitConverter.Int64BitsToDouble(big ? BinaryPrimitives.ReadInt64BigEndian(data) : BinaryPrimitives.ReadInt64LittleEndian(data));
            var bits = big ? BinaryPrimitives.ReadInt32BigEndian(data) : BinaryPrimitives.ReadInt32LittleEndian(data);
            return field.DataType == "INTE" ? bits : BitConverter.Int32BitsToSingle(bits);
        }
        throw Error("INDEX", "The selected cell is outside indexed artifact data.");
    }
    private static EclipseDeckPackageException Error(string suffix, string message) => new("ECLIPSE_GRID_REGION_" + suffix, message);
}
