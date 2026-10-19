using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal static class EclipseWetGasPvtEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseWetGasPvtParameters p,CancellationToken token)
    {
        if(package.Files.Count!=1||Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","PVTG editing requires matching single DATA.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256||!p.MatchesInspection(EclipseDataInspector.InspectPackage(package.Files,p.SourceFile)))throw Error("SOURCE","The complete source branches or source SHA changed.");
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);var lines=Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant).Where(l=>l.Length>0).ToArray();
        var edits=new List<(int Offset,int Length,string Value)>();
        foreach(var row in p.Records.SelectMany(record=>record.Rows)) {
            if(row.LineNumber>lines.Length)throw Error("ROW","PVTG row no longer exists.");var line=lines[row.LineNumber-1];var raw=line.Value.Split("--",2)[0];var parts=Regex.Matches(raw,@"[^\s/]+|/",RegexOptions.CultureInvariant).ToArray();var count=row.Values.Count;
            if(parts.Length!=count&&!(parts.Length==count+1&&parts[^1].Value=="/")||!parts[..count].Select(m=>m.Value).SequenceEqual(row.Values))throw Error("ROW","Each PVTG row must be explicit, complete and on its inspected line.");
            var column=p.Column(row);EclipsePvtwReader.Number(row.Values[column],out var original);var target=original*p.Multiplier;if(!double.IsFinite(target)||target<=0||target>p.Maximum)throw Error("VALUE","Scaled wet-gas PVT value exceeds supported bounds.");
            edits.Add((line.Index+parts[column].Index,parts[column].Length,target.ToString("G17",CultureInfo.InvariantCulture)));
        }
        foreach(var edit in edits.OrderByDescending(e=>e.Offset))text=text[..edit.Offset]+edit.Value+text[(edit.Offset+edit.Length)..];
        await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text),token);
        return $"Applied PVTG {p.Field} multiplier {p.Multiplier:G17} to {p.Records.Length} branches; pressure, Rv, other property and all remaining bytes retained.";
    }
    private static EclipseDeckPackageException Error(string suffix,string message)=>new("ECLIPSE_PVTG_"+suffix,message);
}
