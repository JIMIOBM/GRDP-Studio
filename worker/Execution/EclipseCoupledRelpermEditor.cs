using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal static class EclipseCoupledRelpermEditor
{
    internal static async Task<string> ApplyAsync(EclipseDeckPackage package,EclipseCoupledRelpermParameters p,CancellationToken token){
        if(package.Files.Count!=1||Path.GetFileName(package.MainFile)!=p.SourceFile)throw Error("PACKAGE","Relative permeability editing requires matching single DATA.");
        var bytes=await File.ReadAllBytesAsync(package.MainFile,token);
        if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=p.SourceSha256||!p.MatchesInspection(EclipseDataInspector.InspectPackage(package.Files,p.SourceFile)))throw Error("SOURCE","The full source table or SHA changed.");
        var utf8=new UTF8Encoding(false,true);var text=utf8.GetString(bytes);var lines=Regex.Matches(text,@"[^\r\n]*(?:\r\n|\r|\n|$)",RegexOptions.CultureInvariant).Where(l=>l.Length>0).ToArray();
        var edits=new List<(int Offset,int Length,string Value)>();
        foreach(var table in p.Tables.Where(t=>p.Selected(t.Keyword)))foreach(var row in table.Rows){
            if(row.LineNumber>lines.Length)throw Error("ROW","Relative permeability row no longer exists.");
            var line=lines[row.LineNumber-1];var raw=line.Value.Split("--",2)[0];var parts=Regex.Matches(raw,@"[^\s/]+|/",RegexOptions.CultureInvariant).ToArray();
            if(parts.Length is not(4 or 5)||parts.Length==5&&parts[^1].Value!="/"||!parts[..4].Select(m=>m.Value).SequenceEqual(row.Values))throw Error("ROW","Each source row must match its inspected line.");
            foreach(var column in new[]{p.Column}){
                EclipsePvtwReader.Number(row.Values[column],out var kr);var target=kr*p.Multiplier;
                if(!double.IsFinite(target)||target<0||target>1)throw Error("VALUE","Scaled relative permeability is outside [0,1].");
                // Keep zero endpoints byte-identical as well as numerically identical.
                if(target!=kr)edits.Add((line.Index+parts[column].Index,parts[column].Length,target.ToString("G17",CultureInfo.InvariantCulture)));
            }
        }
        if(edits.Count==0)throw Error("VALUE","The relative permeability scenario has no changed source values.");
        foreach(var edit in edits.OrderByDescending(e=>e.Offset))text=text[..edit.Offset]+edit.Value+text[(edit.Offset+edit.Length)..];
        if(EclipseCoupledRelpermReader.Read(text,p.SourceFile) is null)throw Error("VALUE","Edited relative permeability violates source table relationships.");
        await File.WriteAllBytesAsync(package.MainFile,utf8.GetBytes(text),token);
        return $"Applied {p.Field} relative permeability multiplier {p.Multiplier:G17}; SWOF/SGOF saturation/Pc preserved, oil columns scaled together.";
    }
    private static EclipseDeckPackageException Error(string suffix,string message)=>new("ECLIPSE_COUPLED_RELPERM_"+suffix,message);
}
