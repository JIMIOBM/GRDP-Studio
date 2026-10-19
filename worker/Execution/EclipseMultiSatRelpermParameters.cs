using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseMultiSatRelpermParameters(int RegionNumber,EclipseCoupledRelpermParameters Edit)
{
    internal const string Schema="eclipse-multi-sat-relperm-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseMultiSatRelpermParameters? result){
        result=null;
        string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","unit","multiplier","field","tables","regionNumber"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=9||!value.TryGetProperty("schemaVersion",out var schema)||schema.ValueKind!=JsonValueKind.String||schema.GetString()!=Schema
            ||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)
            ||!value.TryGetProperty("regionNumber",out var region)||region.ValueKind!=JsonValueKind.Number||!region.TryGetInt32(out var number)||number is <1 or >8)return false;
        var node=JsonNode.Parse(value.GetRawText())!.AsObject();node.Remove("regionNumber");node["schemaVersion"]=EclipseCoupledRelpermParameters.Schema;
        using var document=JsonDocument.Parse(node.ToJsonString());if(!EclipseCoupledRelpermParameters.TryParse(document.RootElement,out var edit))return false;
        result=new(number,edit!);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.MultiSatRelpermContext;var region=c?.Regions.SingleOrDefault(r=>r.Number==RegionNumber);
        return c is not null&&c.SchemaVersion=="eclipse-multi-sat-relperm-context/1"&&c.SourceFile==Edit.SourceFile&&region is not null
            &&Edit.MatchesInspection(inspection with {CoupledRelpermContext=new("eclipse-coupled-relperm-context/1",c.SourceFile,region.Tables)});
    }
}
