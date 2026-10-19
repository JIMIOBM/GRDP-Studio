using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal static class EclipseMultiLiveOilPvtEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseMultiLiveOilPvtParameters parameters,CancellationToken token)
    {
        var p=parameters.Edit;
        if(package.Files.Count!=1||Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","PVTO editing requires matching single DATA.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256||!parameters.MatchesInspection(EclipseDataInspector.InspectPackage(package.Files,p.SourceFile)))throw Error("SOURCE","The complete source branches or source SHA changed.");
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);var lines=Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant).Where(l=>l.Length>0).ToArray();
        var edits=new List<(int Offset,int Length,string Value)>();
        foreach(var row in p.Records.SelectMany(record=>record.Rows)) {
            if(row.LineNumber>lines.Length)throw Error("ROW","PVTO row no longer exists.");var line=lines[row.LineNumber-1];var raw=line.Value.Split("--",2)[0];var parts=Regex.Matches(raw,@"[^\s/]+|/",RegexOptions.CultureInvariant).ToArray();var count=row.Values.Count;
            if(parts.Length!=count&&!(parts.Length==count+1&&parts[^1].Value=="/")||!parts[..count].Select(m=>m.Value).SequenceEqual(row.Values))throw Error("ROW","Each PVTO row must be explicit, complete and on its inspected line.");
            var column=p.Column(row);EclipsePvtwReader.Number(row.Values[column],out var original);var target=original*p.Multiplier;if(!double.IsFinite(target)||target<=0||target>p.Maximum)throw Error("VALUE","Scaled live-oil PVT value exceeds supported bounds.");
            edits.Add((line.Index+parts[column].Index,parts[column].Length,target.ToString("G17",CultureInfo.InvariantCulture)));
        }
        foreach(var edit in edits.OrderByDescending(e=>e.Offset))text=text[..edit.Offset]+edit.Value+text[(edit.Offset+edit.Length)..];
        if(EclipseMultiLiveOilPvtReader.Read(text,p.SourceFile) is null && EclipseMultiSatLiveOilPvtReader.Read(text,p.SourceFile) is null)throw Error("VALUE","Edited live-oil table violates region or equilibration relationships.");
        await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text),token);
        return $"Applied PVT region {parameters.RegionNumber} PVTO {p.Field} multiplier {p.Multiplier:G17} to {p.Records.Length} branches; Rs, pressure, other property and all remaining bytes retained.";
    }
    private static EclipseDeckPackageException Error(string suffix,string message)=>new("ECLIPSE_MULTI_PVTO_"+suffix,message);
}
