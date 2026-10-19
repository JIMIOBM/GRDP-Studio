using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseLiveOilPvtParameters(long BaselineRunId,string SourceFile,string SourceSha256,int KeywordLineNumber,string Unit,double Multiplier,EclipseLiveOilPvtRecord[] Records,string Field)
{
    internal const string Schema="eclipse-pvto-parameters/1";
    internal int Column(EclipseOilPvtRow row)=>row.Values.Count-(Field=="FORMATION_VOLUME_FACTOR"?2:1);
    internal double Maximum=>Field=="FORMATION_VOLUME_FACTOR"?10:1000;
    internal static bool TryParse(JsonElement value,out EclipseLiveOilPvtParameters? result)
    {
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","records","field"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        var field=Text("field");
        if(Text("schemaVersion")!=Schema||field is not ("VISCOSITY" or "FORMATION_VOLUME_FACTOR")
            ||(field=="VISCOSITY"?Text("unit")!="cP":Text("unit") is not ("rb/stb" or "rm3/sm3" or "rcc/scc"))
            ||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||!Regex.IsMatch(Text("sourceSha256"),"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)
            ||value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number||!value.GetProperty("baselineRunId").TryGetInt64(out var baseline)||baseline<=0
            ||value.GetProperty("keywordLineNumber").ValueKind!=JsonValueKind.Number||!value.GetProperty("keywordLineNumber").TryGetInt32(out var keywordLine)||keywordLine<=0||keywordLine>2_000_000
            ||value.GetProperty("multiplier").ValueKind!=JsonValueKind.Number||!value.GetProperty("multiplier").TryGetDouble(out var multiplier)||!double.IsFinite(multiplier)||multiplier<.000001||multiplier>100||multiplier==1)return false;
        var raw=value.GetProperty("records");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength() is <2 or >32)return false;
        var records=new List<EclipseLiveOilPvtRecord>();
        foreach(var record in raw.EnumerateArray()) {
            if(record.ValueKind!=JsonValueKind.Object||record.EnumerateObject().Count()!=1||!record.TryGetProperty("rows",out var rawRows)||rawRows.ValueKind!=JsonValueKind.Array||rawRows.GetArrayLength() is <1 or >64)return false;
            var rows=new List<EclipseOilPvtRow>();
            foreach(var row in rawRows.EnumerateArray()) {
                if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=2||!row.TryGetProperty("lineNumber",out var line)||!row.TryGetProperty("values",out var values)
                    ||line.ValueKind!=JsonValueKind.Number||!line.TryGetInt32(out var number)||values.ValueKind!=JsonValueKind.Array||values.GetArrayLength()!=(rows.Count==0?4:3)
                    ||values.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String))return false;
                rows.Add(new(number,values.EnumerateArray().Select(v=>v.GetString()!).ToArray()));
            }records.Add(new(rows));
        }
        if(!EclipseLiveOilPvtReader.ValidRecords(records,keywordLine))return false;
        var candidate=new EclipseLiveOilPvtParameters(baseline,Text("sourceFile"),Text("sourceSha256"),keywordLine,Text("unit"),multiplier,records.ToArray(),field);
        foreach(var record in records){double priorTarget=double.PositiveInfinity;foreach(var row in record.Rows){EclipsePvtwReader.Number(row.Values[candidate.Column(row)],out var original);var target=original*multiplier;if(!double.IsFinite(target)||target<=0||target>candidate.Maximum||field=="FORMATION_VOLUME_FACTOR"&&target>=priorTarget)return false;priorTarget=target;}}
        result=candidate;return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection) {
        var c=inspection.LiveOilPvtContext;
        return Unit==(Field=="VISCOSITY"?"cP":EclipseOilPvtParameters.VolumeUnit(inspection.UnitSystem))
            &&c is not null&&c.SchemaVersion=="eclipse-live-oil-pvt-context/1"&&inspection.Phases.Contains("OIL")&&inspection.Phases.Contains("GAS")
            &&c.SourceFile==SourceFile&&c.KeywordLineNumber==KeywordLineNumber&&c.Records.Count==Records.Length&&c.Records.Zip(Records).All(pair=>pair.First.Rows.Count==pair.Second.Rows.Count&&pair.First.Rows.Zip(pair.Second.Rows).All(row=>row.First.LineNumber==row.Second.LineNumber&&row.First.Values.SequenceEqual(row.Second.Values)));
    }
}
