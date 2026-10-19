using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Execution;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal static class EclipseMultiWaterPvtReader
{
    internal static EclipseMultiWaterPvtContext? Read(string text,string sourceFile){
        var pvt=EclipseMultiPvtReader.Read(text,sourceFile)??EclipseMultiSatPvtReader.Read(text,sourceFile)?.Pvt;var counts=pvt?.Regions.Select(r=>r.CellCount).ToArray()??EclipsePureWaterPvtRegionsReader.Read(text);if(counts is null)return null;
        var lines=Regex.Split(text,"\r\n|\r|\n").Select(l=>l.Split("--",2)[0].Trim()).ToArray();
        var headers=lines.Select((l,i)=>(l,i)).Where(v=>v.l.Equals("PVTW",StringComparison.OrdinalIgnoreCase)).Select(v=>v.i).ToArray();if(headers.Length!=1)return null;
        var units=new[]{"FIELD","METRIC","LAB","PVT-M"}.Where(u=>lines.Count(l=>l.Equals(u,StringComparison.OrdinalIgnoreCase))==1).ToArray();if(units.Length!=1)return null;
        var fields=new[]{"REFERENCE_PRESSURE","FORMATION_VOLUME_FACTOR","COMPRESSIBILITY","VISCOSITY","VISCOSIBILITY"};var regions=new List<EclipseWaterPvtRegion>();
        for(int i=headers[0]+1;i<lines.Length;i++){
            if(lines[i].Length==0)continue;
            if(Regex.IsMatch(lines[i],@"^[A-Za-z][A-Za-z0-9-]*(?:\s+=+)?$",RegexOptions.CultureInvariant))break;
            var tokens=Regex.Matches(lines[i],@"[^\s/]+|/",RegexOptions.CultureInvariant).Select(m=>m.Value).ToArray();
            if(tokens.Length is not (5 or 6)||tokens[^1]!="/"||regions.Count>=counts.Length)return null;
            for(int column=0;column<tokens.Length-1;column++)if(!(column==4&&tokens[column]=="1*"))if(tokens[column].Length>64||!EclipsePvtwReader.Number(tokens[column],out var n)||!EclipsePvtwParameters.Scalar(fields[column],EclipsePvtwParameters.UnitFor(units[0],fields[column])!,n))return null;
            regions.Add(new(regions.Count+1,counts[regions.Count],i+1,tokens[..^1]));
        }
        return regions.Count==counts.Length?new(regions.Any(r=>r.RecordValues.Count==5&&r.RecordValues[4]=="1*")?"eclipse-multi-water-pvt-context/3":regions.Any(r=>r.RecordValues.Count==4)?"eclipse-multi-water-pvt-context/2":"eclipse-multi-water-pvt-context/1",sourceFile,counts.Length,regions):null;
    }
}
