using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseCoupledRelpermParameters(long BaselineRunId,string SourceFile,string SourceSha256,double Multiplier,string Field,EclipseRelpermTable[] Tables)
{
    internal const string Schema="eclipse-coupled-relperm-parameters/1";
    internal int Column=>Field=="OIL_KR"?2:1;
    internal bool Selected(string keyword)=>Field=="OIL_KR"||keyword==(Field=="WATER_KR"?"SWOF":"SGOF");
    internal static bool TryParse(JsonElement value,out EclipseCoupledRelpermParameters? result) {
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        if(Text("schemaVersion")!=Schema||Text("unit")!="1"||Text("field") is not("WATER_KR" or "GAS_KR" or "OIL_KR")
            ||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||!Regex.IsMatch(Text("sourceSha256"),"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)
            ||value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number||!value.GetProperty("baselineRunId").TryGetInt64(out var baseline)||baseline<=0
            ||value.GetProperty("multiplier").ValueKind!=JsonValueKind.Number||!value.GetProperty("multiplier").TryGetDouble(out var multiplier)||!double.IsFinite(multiplier)||multiplier<.000001||multiplier>100||multiplier==1)return false;
        var raw=value.GetProperty("tables");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength()!=2)return false;
        var tables=new List<EclipseRelpermTable>();
        foreach(var table in raw.EnumerateArray()) {
            if(table.ValueKind!=JsonValueKind.Object||table.EnumerateObject().Count()!=3||!table.TryGetProperty("keyword",out var keyword)||keyword.ValueKind!=JsonValueKind.String
                ||!table.TryGetProperty("keywordLineNumber",out var keyLine)||keyLine.ValueKind!=JsonValueKind.Number||!keyLine.TryGetInt32(out var keyNumber)
                ||!table.TryGetProperty("rows",out var rawRows)||rawRows.ValueKind!=JsonValueKind.Array||rawRows.GetArrayLength() is <2 or >64)return false;
            var rows=new List<EclipseRelpermRow>();
            foreach(var row in rawRows.EnumerateArray()) {
                if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=2||!row.TryGetProperty("lineNumber",out var line)||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var number)
                    ||!row.TryGetProperty("values",out var values)||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=4||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String))return false;
                rows.Add(new(number,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
            }tables.Add(new(keyword.GetString()!,keyNumber,rows));
        }
        if(!EclipseCoupledRelpermReader.ValidTables(tables))return false;
        var candidate=new EclipseCoupledRelpermParameters(baseline,Text("sourceFile"),Text("sourceSha256"),multiplier,Text("field"),tables.ToArray());var changed=false;
        foreach(var table in tables.Where(t=>candidate.Selected(t.Keyword)))foreach(var row in table.Rows){EclipsePvtwReader.Number(row.Values[candidate.Column],out var original);var target=original*multiplier;if(!double.IsFinite(target)||target<0||target>1)return false;changed|=target!=original;}
        if(!changed)return false;result=candidate;return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection) {
        var c=inspection.CoupledRelpermContext;
        return inspection.SchemaVersion=="eclipse-data-inspection/4"&&inspection.UnitSystem is ("FIELD" or "METRIC" or "LAB" or "PVT-M")&&inspection.Phases.ToHashSet(StringComparer.Ordinal).SetEquals(["OIL","GAS","WATER"])&&c is not null&&c.SchemaVersion=="eclipse-coupled-relperm-context/1"&&c.SourceFile==SourceFile&&c.Tables.Count==Tables.Length
            &&c.Tables.Zip(Tables).All(pair=>pair.First.Keyword==pair.Second.Keyword&&pair.First.KeywordLineNumber==pair.Second.KeywordLineNumber&&pair.First.Rows.Count==pair.Second.Rows.Count
                &&pair.First.Rows.Zip(pair.Second.Rows).All(row=>row.First.LineNumber==row.Second.LineNumber&&row.First.Values.SequenceEqual(row.Second.Values)));
    }
}
