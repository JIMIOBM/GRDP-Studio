using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseNewWellEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseNewWellParameters p,string gridPath,CancellationToken token)
    {
        if(package.Files.Count!=1 || Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","New water well requires matching single DATA.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);
        if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256)throw Error("SOURCE","The bound source DATA has changed.");
        var inspection=EclipseDataInspector.InspectPackage(package.Files,p.SourceFile);
        if(!p.MatchesInspection(inspection))throw Error("SOURCE","Source timing, well/group identity, capacity, units or selected connection does not match.");
        ValidateCell(p,gridPath);
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);var number=0;
        foreach(Match line in Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant)) {
            if(line.Length==0)continue;if(++number!=p.InsertionLine)continue;
            if(!line.Value.Split("--",2)[0].Trim().Equals("TSTEP",StringComparison.OrdinalIgnoreCase))throw Error("TIMING","Insertion must precede the initial TSTEP keyword.");
            var newline=text.Contains("\r\n",StringComparison.Ordinal)?"\r\n":"\n";
            var block=string.Join(newline,"-- GRDP controlled new water producer; existing wells and controls retained.","WELSPECS",
                string.Create(CultureInfo.InvariantCulture,$" '{p.Well}' '{p.Group}' {p.I} {p.J} {p.ReferenceDepth:G17} 'WAT' /"),"/","COMPDAT",
                string.Create(CultureInfo.InvariantCulture,$" '{p.Well}' {p.I} {p.J} {p.K} {p.K} 'OPEN' 0 0 {p.Diameter:G17} {p.Kh:G17} {p.Skin:G17} /"),"/","WCONPROD",
                string.Create(CultureInfo.InvariantCulture,$" '{p.Well}' 'OPEN' 'WRAT' 1* {p.WaterRate:G17} 3* {p.BhpLimit:G17} /"),"/","");
            await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text.Insert(line.Index,block)),token);
            return $"Added initial water producer {p.Well} in group {p.Group} at I{p.I}/J{p.J}/K{p.K}; original well controls retained.";
        }
        throw Error("TIMING","Initial insertion line no longer exists.");
    }
    internal static void ValidateCell(EclipseNewWellParameters p,string path)
    {
        var grid=EclipseBinaryFieldIndexParser.TryParse(path);var metadata=EclipseGridMetadataParser.TryParse(path);var count=(long)p.Nx*p.Ny*p.Nz;
        if(grid is null||metadata is null||grid.FileName!=p.GridFile||(metadata.Nx,metadata.Ny,metadata.Nz)!=(p.Nx,p.Ny,p.Nz))throw Error("GRID","Bound EGRID dimensions/file changed.");
        var masks=grid.Sections.Where(s=>s.Keyword=="ACTNUM"&&s.Count==count&&s.TimeStep is null&&s.DataType=="INTE"&&s.ElementSize==4).ToArray();
        if(masks.Length!=1)throw Error("GRID","Exactly one full-grid ACTNUM required.");
        long remaining=((long)p.K-1)*p.Nx*p.Ny+((long)p.J-1)*p.Nx+p.I-1;using var stream=File.OpenRead(path);Span<byte> buffer=stackalloc byte[4];
        foreach(var segment in masks[0].Segments){var length=segment.Length/4;if(remaining>=length){remaining-=length;continue;}
            stream.Position=segment.Offset+remaining*4;stream.ReadExactly(buffer);var active=grid.ByteOrder=="BIG"?BinaryPrimitives.ReadInt32BigEndian(buffer):BinaryPrimitives.ReadInt32LittleEndian(buffer);
            if(active<=0)throw Error("INACTIVE_CELL","New completion cell is inactive.");return;}
        throw Error("GRID","Selected completion lies outside ACTNUM data.");
    }
    private static EclipseDeckPackageException Error(string code,string message)=>new("ECLIPSE_NEW_WELL_"+code,message);
}
