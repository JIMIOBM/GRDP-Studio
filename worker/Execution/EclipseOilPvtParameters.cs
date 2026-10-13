using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseOilPvtParameters(long BaselineRunId,string SourceFile,string SourceSha256,int KeywordLineNumber,string Unit,double Multiplier,EclipseOilPvtRow[] Rows)
{
    internal const string Schema="eclipse-pvdo-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseOilPvtParameters? result)
    {
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","rows"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        if(Text("schemaVersion")!=Schema||Text("unit")!="cP"||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||!Regex.IsMatch(Text("sourceSha256"),"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)
            ||value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number||!value.GetProperty("baselineRunId").TryGetInt64(out var baseline)||baseline<=0
            ||value.GetProperty("keywordLineNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("keywordLineNumber").TryGetInt32(out var keywordLine)||keywordLine<=0||keywordLine>2_000_000
            ||value.GetProperty("multiplier").ValueKind!=JsonValueKind.Number||!value.GetProperty("multiplier").TryGetDouble(out var multiplier)||!double.IsFinite(multiplier)||multiplier<.000001||multiplier>100||multiplier==1)return false;
        var raw=value.GetProperty("rows");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength() is <2 or >64)return false;
        var rows=new List<EclipseOilPvtRow>();
        foreach(var row in raw.EnumerateArray()) {
            if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=2||!row.TryGetProperty("lineNumber",out var line)||!row.TryGetProperty("values",out var values)
                ||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var number)||number<=keywordLine||number>2_000_000||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=3
                ||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String||v.GetString()!.Length>64||!EclipsePvtwReader.Number(v.GetString()!,out _)))return false;
            rows.Add(new(number,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
        }
        if(!EclipseOilPvtReader.ValidRows(rows))return false;
        foreach(var row in rows){EclipsePvtwReader.Number(row.Values[2],out var mu);if(!double.IsFinite(mu*multiplier)||mu*multiplier<=0||mu*multiplier>1000)return false;}
        result=new(baseline,Text("sourceFile"),Text("sourceSha256"),keywordLine,Text("unit"),multiplier,rows.ToArray());return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection) {
        var c=inspection.OilPvtContext;
        return c is not null&&c.SchemaVersion=="eclipse-oil-pvt-context/1"&&inspection.Phases.Contains("OIL")&&inspection.UnitSystem is "METRIC" or "FIELD" or "LAB" or "PVT-M"
            &&c.SourceFile==SourceFile&&c.KeywordLineNumber==KeywordLineNumber&&c.Rows.Count==Rows.Length&&c.Rows.Zip(Rows).All(pair=>pair.First.LineNumber==pair.Second.LineNumber&&pair.First.Values.SequenceEqual(pair.Second.Values));
    }
}
