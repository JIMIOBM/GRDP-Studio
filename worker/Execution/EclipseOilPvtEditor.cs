using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal static class EclipseOilPvtEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseOilPvtParameters p,CancellationToken token)
    {
        if(package.Files.Count!=1||Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","PVDO editing requires matching single DATA.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256||!p.MatchesInspection(EclipseDataInspector.InspectPackage(package.Files,p.SourceFile)))throw Error("SOURCE","The complete source table or source SHA changed.");
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);var lines=Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant).Where(l=>l.Length>0).ToArray();
        var edits=new List<(int Offset,int Length,string Value)>();
        foreach(var row in p.Rows) {
            if(row.LineNumber>lines.Length)throw Error("ROW","PVT row no longer exists.");var line=lines[row.LineNumber-1];var raw=line.Value.Split("--",2)[0];var parts=Regex.Matches(raw,@"[^\s/]+|/",RegexOptions.CultureInvariant).ToArray();
            if(parts.Length is not(3 or 4)||parts.Length==4&&parts[^1].Value!="/"||!parts[..3].Select(m=>m.Value).SequenceEqual(row.Values))throw Error("ROW","Each PVDO row must be explicit, complete and on its inspected line.");
            EclipsePvtwReader.Number(row.Values[p.Column],out var original);var target=original*p.Multiplier;if(!double.IsFinite(target)||target<=0||target>p.Maximum)throw Error("VALUE","Scaled oil PVT value exceeds the supported bounds.");
            edits.Add((line.Index+parts[p.Column].Index,parts[p.Column].Length,target.ToString("G17",CultureInfo.InvariantCulture)));
        }
        foreach(var edit in edits.OrderByDescending(e=>e.Offset))text=text[..edit.Offset]+edit.Value+text[(edit.Offset+edit.Length)..];
        await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text),token);
        var retained=p.Column==1?"pressure/viscosity":"pressure/Bo";
        return $"Applied PVDO {p.Field} multiplier {p.Multiplier:G17} to {p.Rows.Length} rows; {retained} columns and all other source bytes retained.";
    }
    private static EclipseDeckPackageException Error(string suffix,string message)=>new("ECLIPSE_PVDO_"+suffix,message);
}
