using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseOilPvtParameters(long BaselineRunId,string SourceFile,string SourceSha256,int KeywordLineNumber,string Unit,double Multiplier,EclipseOilPvtRow[] Rows,string Field="VISCOSITY")
{
    internal const string Schema="eclipse-pvdo-parameters/1";
    internal const string FieldSchema="eclipse-pvdo-parameters/2";
    internal int Column=>Field=="FORMATION_VOLUME_FACTOR"?1:2;
    internal double Maximum=>Field=="FORMATION_VOLUME_FACTOR"?10:1000;
    internal static string VolumeUnit(string? system)=>system switch {"FIELD"=>"rb/stb","LAB"=>"rcc/scc","METRIC" or "PVT-M"=>"rm3/sm3",_=>""};
    internal static bool TryParse(JsonElement value,out EclipseOilPvtParameters? result)
    {
        result=null;string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","keywordLineNumber","unit","multiplier","rows"];
        if(value.ValueKind==JsonValueKind.Object&&value.TryGetProperty("schemaVersion",out var schema)&&schema.ValueKind==JsonValueKind.String&&schema.GetString()==FieldSchema)names=[..names,"field"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=names.Length||!value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.TryGetProperty(key,out var property)&&property.ValueKind==JsonValueKind.String?property.GetString()!:"";
        var field=Text("schemaVersion")==Schema?"VISCOSITY":Text("field");
        if(Text("schemaVersion") is not (Schema or FieldSchema)||field is not ("VISCOSITY" or "FORMATION_VOLUME_FACTOR")
            ||(field=="VISCOSITY"?Text("unit")!="cP":Text("unit") is not ("rb/stb" or "rm3/sm3" or "rcc/scc"))
            ||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)||!Regex.IsMatch(Text("sourceSha256"),"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)
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
        int column=field=="FORMATION_VOLUME_FACTOR"?1:2;double maximum=column==1?10:1000,priorTarget=double.PositiveInfinity;
        foreach(var row in rows){EclipsePvtwReader.Number(row.Values[column],out var original);var target=original*multiplier;if(!double.IsFinite(target)||target<=0||target>maximum||column==1&&target>=priorTarget)return false;priorTarget=target;}
        result=new(baseline,Text("sourceFile"),Text("sourceSha256"),keywordLine,Text("unit"),multiplier,rows.ToArray(),field);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection) {
        var c=inspection.OilPvtContext;
        return (Field is "VISCOSITY" or "FORMATION_VOLUME_FACTOR")&&Unit==(Field=="VISCOSITY"?"cP":VolumeUnit(inspection.UnitSystem))
            &&c is not null&&c.SchemaVersion=="eclipse-oil-pvt-context/1"&&inspection.Phases.Contains("OIL")&&inspection.UnitSystem is "METRIC" or "FIELD" or "LAB" or "PVT-M"
            &&c.SourceFile==SourceFile&&c.KeywordLineNumber==KeywordLineNumber&&c.Rows.Count==Rows.Length&&c.Rows.Zip(Rows).All(pair=>pair.First.LineNumber==pair.Second.LineNumber&&pair.First.Values.SequenceEqual(pair.Second.Values));
    }
}
