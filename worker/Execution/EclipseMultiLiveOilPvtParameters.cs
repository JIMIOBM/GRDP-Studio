using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseMultiLiveOilPvtParameters(int RegionNumber,EclipseRegionGasPvtTable GasTable,EclipseLiveOilPvtParameters Edit)
{
    internal const string Schema="eclipse-multi-pvto-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseMultiLiveOilPvtParameters? result){
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","records","field","regionNumber","gasTable"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)
            ||value.GetProperty("schemaVersion").ValueKind!=JsonValueKind.String||value.GetProperty("schemaVersion").GetString()!=Schema
            ||value.GetProperty("regionNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("regionNumber").TryGetInt32(out var region)||region is <1 or >8)return false;
        var gas=value.GetProperty("gasTable");
        if(gas.ValueKind!=JsonValueKind.Object||gas.EnumerateObject().Count()!=2||!gas.TryGetProperty("keywordLineNumber",out var header)||header.ValueKind!=JsonValueKind.Number||!header.TryGetInt32(out var number)||number is <1 or >2_000_000
            ||!gas.TryGetProperty("rows",out var rawRows)||rawRows.ValueKind!=JsonValueKind.Array||rawRows.GetArrayLength() is <2 or >64)return false;
        var rows=new List<EclipseGasPvtRow>();
        foreach(var raw in rawRows.EnumerateArray()){
            if(raw.ValueKind!=JsonValueKind.Object||raw.EnumerateObject().Count()!=2||!raw.TryGetProperty("lineNumber",out var line)||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var rowNumber)||rowNumber<=number||rowNumber>2_000_000
                ||!raw.TryGetProperty("values",out var values)||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=3||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String||v.GetString()!.Length>64))return false;
            rows.Add(new(rowNumber,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
        }
        if(!EclipseGasPvtReader.ValidRows(rows))return false;
        var node=JsonNode.Parse(value.GetRawText())!.AsObject();node.Remove("regionNumber");node.Remove("gasTable");node["schemaVersion"]=EclipseLiveOilPvtParameters.Schema;
        using var doc=JsonDocument.Parse(node.ToJsonString());if(!EclipseLiveOilPvtParameters.TryParse(doc.RootElement,out var edit)||rows[^1].LineNumber>=edit!.KeywordLineNumber)return false;
        result=new(region,new(number,rows),edit);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.MultiLiveOilPvtContext;var r=c?.Regions.SingleOrDefault(r=>r.Number==RegionNumber);
        return c is not null&&c.SchemaVersion=="eclipse-multi-live-oil-pvt-context/1"&&r is not null&&GasTable.KeywordLineNumber==r.GasTable.KeywordLineNumber&&GasTable.Rows.Count==r.GasTable.Rows.Count
            &&GasTable.Rows.Zip(r.GasTable.Rows).All(p=>p.First.LineNumber==p.Second.LineNumber&&p.First.Values.SequenceEqual(p.Second.Values))
            &&Edit.MatchesInspection(inspection with{LiveOilPvtContext=new("eclipse-live-oil-pvt-context/1",c.SourceFile,r.KeywordLineNumber,r.Records)});
    }
}
