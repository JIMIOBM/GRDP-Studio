using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseMultiSatFamilyIIParameters(int RegionNumber,string Field,EclipseRelpermTable[] Tables,EclipseRelpermParameters Edit)
{
    internal const string Schema="eclipse-multi-sat-relperm-parameters/2";
    internal static bool TryParse(JsonElement value,out EclipseMultiSatFamilyIIParameters? result){
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables","regionNumber"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)
            ||value.GetProperty("schemaVersion").ValueKind!=JsonValueKind.String||value.GetProperty("schemaVersion").GetString()!=Schema
            ||value.GetProperty("regionNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("regionNumber").TryGetInt32(out var region)||region is <1 or >8
            ||value.GetProperty("field").ValueKind!=JsonValueKind.String)return false;
        var field=value.GetProperty("field").GetString();var keyword=field switch{"WATER_KR"=>"SWFN","GAS_KR"=>"SGFN","OIL_KR"=>"SOF3",_=>null};if(keyword is null)return false;
        var raw=value.GetProperty("tables");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength()!=3)return false;var tables=new List<EclipseRelpermTable>();
        foreach(var table in raw.EnumerateArray()){
            if(table.ValueKind!=JsonValueKind.Object||table.EnumerateObject().Count()!=3||!table.TryGetProperty("keyword",out var key)||key.ValueKind!=JsonValueKind.String
                ||!table.TryGetProperty("keywordLineNumber",out var keyLine)||keyLine.ValueKind!=JsonValueKind.Number||!keyLine.TryGetInt32(out var keyNumber)||keyNumber is <1 or >2_000_000
                ||!table.TryGetProperty("rows",out var rawRows)||rawRows.ValueKind!=JsonValueKind.Array||rawRows.GetArrayLength() is <2 or >64)return false;
            var rows=new List<EclipseRelpermRow>();
            foreach(var row in rawRows.EnumerateArray()){
                if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=2||!row.TryGetProperty("lineNumber",out var line)||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var number)||number<=keyNumber||number>2_000_000
                    ||!row.TryGetProperty("values",out var values)||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=3||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String))return false;
                rows.Add(new(number,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
            }tables.Add(new(key.GetString()!,keyNumber,rows));
        }
        if(!EclipseRelpermReader.ValidTables(tables)||tables.Zip(tables.Skip(1)).Any(pair=>pair.Second.KeywordLineNumber<=pair.First.Rows[^1].LineNumber))return false;
        var node=JsonNode.Parse(value.GetRawText())!.AsObject();var selected=node["tables"]!.AsArray().Single(t=>t!["keyword"]!.GetValue<string>()==keyword)!.AsObject();
        var lineNumber=selected["keywordLineNumber"]!.DeepClone();var selectedRows=selected["rows"]!.DeepClone();node.Remove("tables");node.Remove("regionNumber");node.Remove("field");
        node["schemaVersion"]=keyword=="SGFN"?EclipseRelpermParameters.GasSchema:EclipseRelpermParameters.Schema;node["keyword"]=keyword;node["keywordLineNumber"]=lineNumber;node["rows"]=selectedRows;
        using var document=JsonDocument.Parse(node.ToJsonString());if(!EclipseRelpermParameters.TryParse(document.RootElement,out var edit))return false;
        result=new(region,field!,tables.ToArray(),edit!);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.MultiSatRelpermContext;var region=c?.Regions.SingleOrDefault(r=>r.Number==RegionNumber);
        return c is not null&&c.SchemaVersion=="eclipse-multi-sat-relperm-context/2"&&c.SourceFile==Edit.SourceFile&&region is not null&&region.Tables.Count==Tables.Length
            &&region.Tables.Zip(Tables).All(pair=>pair.First.Keyword==pair.Second.Keyword&&pair.First.KeywordLineNumber==pair.Second.KeywordLineNumber&&pair.First.Rows.Count==pair.Second.Rows.Count&&pair.First.Rows.Zip(pair.Second.Rows).All(r=>r.First.LineNumber==r.Second.LineNumber&&r.First.Values.SequenceEqual(r.Second.Values)))
            &&Edit.MatchesInspection(inspection with{RelpermContext=new("eclipse-relperm-context/1",c.SourceFile,region.Tables)});
    }
}
