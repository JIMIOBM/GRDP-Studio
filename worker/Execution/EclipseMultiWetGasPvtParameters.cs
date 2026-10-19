using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseMultiWetGasPvtParameters(int RegionNumber,EclipseRegionLiveOilPvtTable OilTable,EclipseWetGasPvtParameters Edit)
{
    internal const string Schema="eclipse-multi-pvtg-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseMultiWetGasPvtParameters? result){
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","records","field","regionNumber","oilTable"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)
            ||value.GetProperty("schemaVersion").ValueKind!=JsonValueKind.String||value.GetProperty("schemaVersion").GetString()!=Schema
            ||value.GetProperty("regionNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("regionNumber").TryGetInt32(out var region)||region is <1 or >8)return false;
        var oil=value.GetProperty("oilTable");
        if(oil.ValueKind!=JsonValueKind.Object||oil.EnumerateObject().Count()!=2||!oil.TryGetProperty("keywordLineNumber",out var header)||header.ValueKind!=JsonValueKind.Number||!header.TryGetInt32(out var number)||number is <1 or >2_000_000
            ||!oil.TryGetProperty("records",out var rawRecords)||rawRecords.ValueKind!=JsonValueKind.Array||rawRecords.GetArrayLength() is <2 or >32)return false;
        var records=new List<EclipseLiveOilPvtRecord>();
        foreach(var rawRecord in rawRecords.EnumerateArray()){
            if(rawRecord.ValueKind!=JsonValueKind.Object||rawRecord.EnumerateObject().Count()!=1||!rawRecord.TryGetProperty("rows",out var rawRows)||rawRows.ValueKind!=JsonValueKind.Array||rawRows.GetArrayLength() is <1 or >64)return false;
            var rows=new List<EclipseOilPvtRow>();
            foreach(var raw in rawRows.EnumerateArray()){
                if(raw.ValueKind!=JsonValueKind.Object||raw.EnumerateObject().Count()!=2||!raw.TryGetProperty("lineNumber",out var line)||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var rowNumber)||rowNumber<=number||rowNumber>2_000_000
                    ||!raw.TryGetProperty("values",out var values)||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=(rows.Count==0?4:3)||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String||v.GetString()!.Length>64))return false;
                rows.Add(new(rowNumber,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
            }records.Add(new(rows));
        }
        if(!EclipseLiveOilPvtReader.ValidRecords(records,number))return false;
        var node=JsonNode.Parse(value.GetRawText())!.AsObject();node.Remove("regionNumber");node.Remove("oilTable");node["schemaVersion"]=EclipseWetGasPvtParameters.Schema;
        using var doc=JsonDocument.Parse(node.ToJsonString());if(!EclipseWetGasPvtParameters.TryParse(doc.RootElement,out var edit)||edit!.Records[^1].Rows[^1].LineNumber>=number)return false;
        result=new(region,new(number,records),edit);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.MultiWetGasPvtContext;var r=c?.Regions.SingleOrDefault(r=>r.Number==RegionNumber);
        return c is not null&&c.SchemaVersion=="eclipse-multi-wet-gas-pvt-context/1"&&r is not null&&OilTable.KeywordLineNumber==r.OilTable.KeywordLineNumber&&OilTable.Records.Count==r.OilTable.Records.Count
            &&OilTable.Records.Zip(r.OilTable.Records).All(p=>p.First.Rows.Count==p.Second.Rows.Count&&p.First.Rows.Zip(p.Second.Rows).All(row=>row.First.LineNumber==row.Second.LineNumber&&row.First.Values.SequenceEqual(row.Second.Values)))
            &&Edit.MatchesInspection(inspection with{WetGasPvtContext=new("eclipse-wet-gas-pvt-context/1",c.SourceFile,r.KeywordLineNumber,r.Records)});
    }
}
