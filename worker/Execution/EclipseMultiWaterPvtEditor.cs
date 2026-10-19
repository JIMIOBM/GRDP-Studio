using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal static class EclipseMultiWaterPvtEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseMultiWaterPvtParameters parameters,CancellationToken token){
        var p=parameters.Edit;
        if(package.Files.Count!=1||Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","Water editing requires matching single DATA.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256||!parameters.MatchesInspection(EclipseDataInspector.InspectPackage(package.Files,p.SourceFile)))throw Error("SOURCE","The selected water region or full source SHA changed.");
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);var lines=Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant).Where(l=>l.Length>0).ToArray();
        if(p.LineNumber>lines.Length)throw Error("ROW","PVTW row no longer exists.");var line=lines[p.LineNumber-1];var raw=line.Value.Split("--",2)[0];var parts=Regex.Matches(raw,@"[^\s/]+|/",RegexOptions.CultureInvariant).ToArray();
        if(parts.Length!=parameters.RecordValues.Count+1||parts[^1].Value!="/"||!parts[..^1].Select(v=>v.Value).SequenceEqual(parameters.RecordValues))throw Error("ROW","PVTW must match its complete original water source record.");
        var target=parts[p.ValueIndex];var offset=line.Index+target.Index;bool omitted=p.ValueIndex==4&&parameters.RecordValues.Count==4;var replacement=p.TargetValue.ToString("G17",CultureInfo.InvariantCulture)+(omitted?" ":"");text=text[..offset]+replacement+text[(offset+(omitted?0:target.Length))..];
        if(EclipseMultiWaterPvtReader.Read(text,p.SourceFile) is null)throw Error("VALUE","Edited water properties violate supported source bounds.");
        await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text),token);
        return $"Applied PVT region {parameters.RegionNumber} water {p.Field} target {p.TargetValue:G17}; all other water records, oil/gas tables, maps and source bytes retained.";
    }
    private static EclipseDeckPackageException Error(string suffix,string message)=>new("ECLIPSE_MULTI_PVTW_"+suffix,message);
}
