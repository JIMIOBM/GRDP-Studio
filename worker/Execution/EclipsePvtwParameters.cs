using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipsePvtwParameters(long BaselineRunId,string SourceFile,string SourceSha256,int LineNumber,string Field,string Unit,double OriginalValue,double TargetValue,string[] RecordValues)
{
    internal const string Schema="eclipse-pvtw-parameters/1";
    internal const string Schema2="eclipse-pvtw-parameters/2";
    internal int ValueIndex=>Index(Field);
    internal static int Index(string field)=>field switch {"REFERENCE_PRESSURE"=>0,"FORMATION_VOLUME_FACTOR"=>1,"COMPRESSIBILITY"=>2,"VISCOSITY"=>3,"VISCOSIBILITY"=>4,_=>-1};
    internal static string? UnitFor(string system,string field)=>Index(field)<0?null:field switch {
        "VISCOSITY"=>"cP", "FORMATION_VOLUME_FACTOR"=>system=="FIELD"?"rb/stb":system=="LAB"?"rcc/scc":"rm3/sm3",
        "REFERENCE_PRESSURE"=>system=="FIELD"?"psia":system is "LAB" or "PVT-M"?"atma":"barsa",
        _=>system=="FIELD"?"1/psi":system is "LAB" or "PVT-M"?"1/atm":"1/bar"};
    internal static bool Scalar(string field,string unit,double n)=>double.IsFinite(n)&&(field switch {
        "REFERENCE_PRESSURE"=>n>=0 && n*(unit=="psia"?0.0689475729:unit=="atma"?1.01325:1)<=20000,
        "FORMATION_VOLUME_FACTOR"=>n>0&&n<=10, "VISCOSITY"=>n>0&&n<=1000,
        "COMPRESSIBILITY" or "VISCOSIBILITY"=>n>=0&&n*(unit=="1/psi"?14.5037738:unit=="1/atm"?1/1.01325:1)<=.001,
        _=>false});
    internal static bool TryParse(JsonElement value,out EclipsePvtwParameters? result)
    {
        result=null;
        string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","lineNumber","field","unit","originalValue","targetValue","recordValues"];
        if(value.ValueKind!=JsonValueKind.Object || value.EnumerateObject().Count()!=names.Length || !value.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        if(Text("schemaVersion") is not (Schema or Schema2) || value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number || !value.GetProperty("baselineRunId").TryGetInt64(out var baseline)||baseline<=0
            ||value.GetProperty("lineNumber").ValueKind!=JsonValueKind.Number ||!value.GetProperty("lineNumber").TryGetInt32(out var line)||line<=0||line>2_000_000
            ||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)
            ||!Regex.IsMatch(Text("sourceSha256"),"^[a-f0-9]{64}$",RegexOptions.CultureInvariant)||(Text("schemaVersion")==Schema&&Text("field")!="VISCOSITY")||!new[]{"METRIC","FIELD","LAB","PVT-M"}.Any(system=>UnitFor(system,Text("field"))==Text("unit")))return false;
        bool Num(string key,out double n) { n=0;return value.GetProperty(key).ValueKind==JsonValueKind.Number&&value.GetProperty(key).TryGetDouble(out n)&&Scalar(Text("field"),Text("unit"),n); }
        if(!Num("originalValue",out var original)||!Num("targetValue",out var target)||original==target)return false;
        var raw=value.GetProperty("recordValues");if(raw.ValueKind!=JsonValueKind.Array||raw.GetArrayLength()!=5||raw.EnumerateArray().Any(v=>v.ValueKind!=JsonValueKind.String||v.GetString()!.Length>64||!EclipsePvtwReader.Number(v.GetString()!,out _)))return false;
        var row=raw.EnumerateArray().Select(v=>v.GetString()!).ToArray();EclipsePvtwReader.Number(row[Index(Text("field"))],out var source);
        if(source!=original)return false;
        for(var i=0;i<5;i++){EclipsePvtwReader.Number(row[i],out var n);if((i is 1 or 3)?n<=0:n<0)return false;}
        result=new(baseline,Text("sourceFile"),Text("sourceSha256"),line,Text("field"),Text("unit"),original,target,row);return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection)
    {
        var c=inspection.PvtwContext;
        return c is not null&&inspection.UnitSystem is "METRIC" or "FIELD" or "LAB" or "PVT-M"&&inspection.Phases.SequenceEqual(["WATER"])&&UnitFor(inspection.UnitSystem!,Field)==Unit
            &&c.SchemaVersion=="eclipse-pvtw-context/1"&&c.SourceFile==SourceFile&&c.LineNumber==LineNumber&&c.RecordValues.SequenceEqual(RecordValues);
    }
}
