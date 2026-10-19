using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseMultiPvtParameters(int RegionNumber,string Keyword,string Field,EclipsePvtTable[] Tables,EclipseOilPvtParameters? Oil,EclipseGasPvtParameters? Gas)
{
    internal const string Schema="eclipse-multi-pvt-parameters/1";
    internal string SourceFile=>Oil?.SourceFile??Gas!.SourceFile;
    internal string SourceSha256=>Oil?.SourceSha256??Gas!.SourceSha256;
    internal double Multiplier=>Oil?.Multiplier??Gas!.Multiplier;
    internal int Column=>Oil?.Column??Gas!.Column;
    internal double Maximum=>Oil?.Maximum??Gas!.Maximum;
    internal EclipsePvtTable Selected=>Tables.Single(t=>t.Keyword==Keyword);
    internal static bool TryParse(JsonElement value,out EclipseMultiPvtParameters? result){
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables","regionNumber","keyword"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)
            ||value.GetProperty("schemaVersion").ValueKind!=JsonValueKind.String||value.GetProperty("schemaVersion").GetString()!=Schema
            ||value.GetProperty("regionNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("regionNumber").TryGetInt32(out var region)||region is <1 or >8
            ||value.GetProperty("keyword").ValueKind!=JsonValueKind.String||value.GetProperty("keyword").GetString() is not("PVDO" or "PVDG"))return false;
        var keyword=value.GetProperty("keyword").GetString()!;var raw=value.GetProperty("tables");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength()!=2)return false;
        var tables=new List<EclipsePvtTable>();int previous=0;
        foreach(var table in raw.EnumerateArray()){
            if(table.ValueKind!=JsonValueKind.Object||table.EnumerateObject().Count()!=3||!table.TryGetProperty("keyword",out var key)||key.ValueKind!=JsonValueKind.String||key.GetString()!=(tables.Count==0?"PVDO":"PVDG")
                ||!table.TryGetProperty("keywordLineNumber",out var keyLine)||keyLine.ValueKind!=JsonValueKind.Number||!keyLine.TryGetInt32(out var keyNumber)||keyNumber<=previous||keyNumber>2_000_000
                ||!table.TryGetProperty("rows",out var rawRows)||rawRows.ValueKind!=JsonValueKind.Array||rawRows.GetArrayLength() is <2 or >64)return false;
            var rows=new List<EclipsePvtRow>();
            foreach(var row in rawRows.EnumerateArray()){
                if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=2||!row.TryGetProperty("lineNumber",out var line)||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var number)||number<=keyNumber||number>2_000_000
                    ||!row.TryGetProperty("values",out var values)||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=3||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String))return false;
                rows.Add(new(number,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
            }
            if(!EclipseMultiPvtReader.ValidRows(key.GetString()!,rows))return false;tables.Add(new(key.GetString()!,keyNumber,rows));previous=rows[^1].LineNumber;
        }
        var node=JsonNode.Parse(value.GetRawText())!.AsObject();var selected=node["tables"]!.AsArray().Single(t=>t!["keyword"]!.GetValue<string>()==keyword)!;
        node["keywordLineNumber"]=selected["keywordLineNumber"]!.DeepClone();node["rows"]=selected["rows"]!.DeepClone();node.Remove("tables");node.Remove("regionNumber");node.Remove("keyword");
        node["schemaVersion"]=keyword=="PVDO"?EclipseOilPvtParameters.FieldSchema:EclipseGasPvtParameters.FieldSchema;
        using var doc=JsonDocument.Parse(node.ToJsonString());EclipseOilPvtParameters? oil=null;EclipseGasPvtParameters? gas=null;
        if(keyword=="PVDO"?!EclipseOilPvtParameters.TryParse(doc.RootElement,out oil):!EclipseGasPvtParameters.TryParse(doc.RootElement,out gas))return false;
        result=new(region,keyword,oil?.Field??gas!.Field,tables.ToArray(),oil,gas);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.MultiPvtContext;var r=c?.Regions.SingleOrDefault(r=>r.Number==RegionNumber);
        if(c is null||c.SchemaVersion!="eclipse-multi-pvt-context/1"||c.SourceFile!=SourceFile||r is null||r.Tables.Count!=Tables.Length||!r.Tables.Zip(Tables).All(p=>p.First.Keyword==p.Second.Keyword&&p.First.KeywordLineNumber==p.Second.KeywordLineNumber&&p.First.Rows.Count==p.Second.Rows.Count&&p.First.Rows.Zip(p.Second.Rows).All(v=>v.First.LineNumber==v.Second.LineNumber&&v.First.Values.SequenceEqual(v.Second.Values))))return false;
        var t=Selected;return Oil is not null?Oil.MatchesInspection(inspection with{OilPvtContext=new("eclipse-oil-pvt-context/1",c.SourceFile,t.KeywordLineNumber,t.Rows.Select(r=>new EclipseOilPvtRow(r.LineNumber,r.Values)).ToArray())}):Gas!.MatchesInspection(inspection with{GasPvtContext=new("eclipse-gas-pvt-context/1",c.SourceFile,t.KeywordLineNumber,t.Rows.Select(r=>new EclipseGasPvtRow(r.LineNumber,r.Values)).ToArray())});
    }
}
