using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseMultiWaterPvtParameters(int RegionNumber,EclipsePvtwParameters Edit,string SchemaVersion="eclipse-multi-pvtw-parameters/1",IReadOnlyList<string>? SourceRecordValues=null)
{
    internal const string Schema="eclipse-multi-pvtw-parameters/1";
    internal const string Schema2="eclipse-multi-pvtw-parameters/2";
    internal const string Schema3="eclipse-multi-pvtw-parameters/3";
    internal IReadOnlyList<string> RecordValues=>SourceRecordValues??Edit.RecordValues;
    internal static bool TryParse(JsonElement value,out EclipseMultiWaterPvtParameters? result){
        result=null;string[] names=["schemaVersion","regionNumber","baselineRunId","sourceFile","sourceSha256","lineNumber","field","unit","originalValue","targetValue","recordValues"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names)
            ||value.GetProperty("schemaVersion").ValueKind!=JsonValueKind.String||value.GetProperty("schemaVersion").GetString() is not (Schema or Schema2 or Schema3)||value.GetProperty("regionNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("regionNumber").TryGetInt32(out var region)||region is <1 or >8)return false;
        var version=value.GetProperty("schemaVersion").GetString()!;var values=value.GetProperty("recordValues");
        if(values.ValueKind!=JsonValueKind.Array||!(values.GetArrayLength()==5||((version==Schema2||version==Schema3)&&values.GetArrayLength()==4))||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String))return false;
        var original=values.EnumerateArray().Select(v=>v.GetString()!).ToArray();
        if(original.Length==5&&original[4]=="1*"&&version!=Schema3)return false;
        var node=JsonNode.Parse(value.GetRawText())!.AsObject();node.Remove("regionNumber");node["schemaVersion"]=EclipsePvtwParameters.Schema2;if(original.Length==4)node["recordValues"]!.AsArray().Add("0");else if(original[4]=="1*")node["recordValues"]!.AsArray()[4]="0";using var doc=JsonDocument.Parse(node.ToJsonString());
        if(!EclipsePvtwParameters.TryParse(doc.RootElement,out var edit))return false;result=new(region,edit!,version,original);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection){
        var c=inspection.MultiWaterPvtContext;var r=c?.Regions.SingleOrDefault(r=>r.Number==RegionNumber);
        return c is not null&&c.SchemaVersion==(SchemaVersion==Schema3?"eclipse-multi-water-pvt-context/3":SchemaVersion==Schema2?"eclipse-multi-water-pvt-context/2":"eclipse-multi-water-pvt-context/1")&&r is not null&&inspection.Phases.Contains("WATER")&&Edit.SourceFile==c.SourceFile&&Edit.LineNumber==r.LineNumber&&RecordValues.SequenceEqual(r.RecordValues)&&Edit.Unit==EclipsePvtwParameters.UnitFor(inspection.UnitSystem!,Edit.Field);
    }
}
