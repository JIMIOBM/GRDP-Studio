using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal static class EclipseNewCompletionEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseNewCompletionParameters p,string gridPath,CancellationToken token)
    {
        if(package.Files.Count!=1||Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","A matching single DATA source is required.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);
        if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256)throw Error("SOURCE","The bound source DATA has changed.");
        if(!p.MatchesInspection(EclipseDataInspector.InspectPackage(package.Files,p.SourceFile)))throw Error("SOURCE","Existing well, timing, capacity, units or connection does not match.");
        ValidateCell(p,gridPath);
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);int number=0;
        foreach(Match line in Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant)){
            if(line.Length==0)continue;if(++number!=p.InsertionLine)continue;
            if(!line.Value.Split("--",2)[0].Trim().Equals("TSTEP",StringComparison.OrdinalIgnoreCase))throw Error("TIMING","The connection must precede the initial TSTEP.");
            string newline=text.Contains("\r\n",StringComparison.Ordinal)?"\r\n":"\n";
            string block=string.Join(newline,"-- GRDP controlled extra connection; existing well and controls retained.","COMPDAT",
                string.Create(CultureInfo.InvariantCulture,$" '{p.Well}' {p.I} {p.J} {p.K} {p.K} 'OPEN' 0 0 {p.Diameter:G17} {p.Kh:G17} {p.Skin:G17} /"),"/","");
            await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text.Insert(line.Index,block)),token);
            return $"Added initial connection to existing producer {p.Well} at I{p.I}/J{p.J}/K{p.K}; original well identity and controls retained.";
        }
        throw Error("TIMING","Initial insertion line no longer exists.");
    }
    internal static void ValidateCell(EclipseNewCompletionParameters p,string path)
    {
        var file=EclipseBinaryFieldIndexParser.TryParse(path);var metadata=EclipseGridMetadataParser.TryParse(path);
        if(file is null||metadata is null||file.FileName!=p.GridFile||(metadata.Nx,metadata.Ny,metadata.Nz)!=(p.Nx,p.Ny,p.Nz))throw Error("GRID","Bound EGRID dimensions/file changed.");
        long count=(long)p.Nx*p.Ny*p.Nz;
        var masks=file.Sections.Where(f=>f.Keyword=="ACTNUM"&&f.Count==count&&f.TimeStep is null&&f.DataType=="INTE"&&f.ElementSize==4).ToArray();
        if(masks.Length!=1)throw Error("GRID","Exactly one full-grid ACTNUM is required.");
        long remaining=((long)p.K-1)*p.Nx*p.Ny+((long)p.J-1)*p.Nx+p.I-1;
        using var stream=File.OpenRead(path);Span<byte> buffer=stackalloc byte[4];
        foreach(var segment in masks[0].Segments){
            long elements=segment.Length/4;if(remaining>=elements){remaining-=elements;continue;}
            stream.Position=segment.Offset+remaining*4;stream.ReadExactly(buffer);
            int active=file.ByteOrder=="BIG"?BinaryPrimitives.ReadInt32BigEndian(buffer):BinaryPrimitives.ReadInt32LittleEndian(buffer);
            if(active<=0)throw Error("INACTIVE_CELL","New completion must be in an active cell.");return;
        }
        throw Error("GRID","Selected connection lies outside ACTNUM data.");
    }
    private static EclipseDeckPackageException Error(string code,string message)=>new("ECLIPSE_NEW_COMPLETION_"+code,message);
}
