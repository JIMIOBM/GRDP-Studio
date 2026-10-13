using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseRelpermParameters(long BaselineRunId,string SourceFile,string SourceSha256,string Keyword,int KeywordLineNumber,string Unit,double Multiplier,EclipseRelpermRow[] Rows)
{
    internal const string Schema="eclipse-relperm-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseRelpermParameters? result){
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","keyword","keywordLineNumber","unit","multiplier","rows"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        if(Text("schemaVersion")!=Schema||Text("keyword") is not("SWFN" or "SOF3")||Text("unit")!="1"||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||!Regex.IsMatch(Text("sourceSha256"),"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)
            ||value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number||!value.GetProperty("baselineRunId").TryGetInt64(out var baseline)||baseline<=0
            ||value.GetProperty("keywordLineNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("keywordLineNumber").TryGetInt32(out var keywordLine)||keywordLine<=0||keywordLine>2000000
            ||value.GetProperty("multiplier").ValueKind!=JsonValueKind.Number||!value.GetProperty("multiplier").TryGetDouble(out var multiplier)||!double.IsFinite(multiplier)||multiplier<.000001||multiplier>100||multiplier==1)return false;
        var raw=value.GetProperty("rows");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength() is <2 or >64)return false;
        var rows=new List<EclipseRelpermRow>();
        foreach(var row in raw.EnumerateArray()){
            if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=2||!row.TryGetProperty("lineNumber",out var line)||!row.TryGetProperty("values",out var values)
                ||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var number)||number<=keywordLine||number>2000000||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=3
                ||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String||v.GetString()!.Length>64||!EclipsePvtwReader.Number(v.GetString()!,out _)))return false;
            rows.Add(new(number,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
        }
        if(!EclipseRelpermReader.ValidRows(Text("keyword"),rows))return false;
        bool changed=false;
        foreach(var row in rows)foreach(var column in Text("keyword")=="SWFN"?new[]{1}:new[]{1,2}){
            EclipsePvtwReader.Number(row.Values[column],out var kr);if(!double.IsFinite(kr*multiplier)||kr*multiplier<0||kr*multiplier>1)return false;changed|=kr*multiplier!=kr;
        }
        if(!changed)return false;
        result=new(baseline,Text("sourceFile"),Text("sourceSha256"),Text("keyword"),keywordLine,Text("unit"),multiplier,rows.ToArray());return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.RelpermContext;if(c is null||c.SchemaVersion!="eclipse-relperm-context/1"||c.SourceFile!=SourceFile||!EclipseRelpermReader.ValidTables(c.Tables)||inspection.UnitSystem is not("METRIC" or "FIELD" or "LAB" or "PVT-M"))return false;
        var table=c.Tables.SingleOrDefault(t=>t.Keyword==Keyword);
        return table is not null&&table.KeywordLineNumber==KeywordLineNumber&&table.Rows.Count==Rows.Length&&table.Rows.Zip(Rows).All(pair=>pair.First.LineNumber==pair.Second.LineNumber&&pair.First.Values.SequenceEqual(pair.Second.Values));
    }
}
