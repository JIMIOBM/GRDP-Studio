using System.Text.Json;
using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseGroupStructureParameters(long BaselineRunId, string ParentGroup, string ObjectName,
    string Keyword, string SourceFile, int LineNumber, string Unit, double OriginalValue, double TargetValue, IReadOnlyList<string> Members)
{
    internal const string Schema = "eclipse-group-structure-parameters/1";
    internal static bool TryParse(JsonElement p, out EclipseGroupStructureParameters? result)
    {
        result=null;
        string[] fields=["schemaVersion","baselineRunId","phase","parentGroup","objectName","keyword","sourceFile","lineNumber","unit","originalValue","targetValue","members"];
        if(p.ValueKind!=JsonValueKind.Object || p.EnumerateObject().Count()!=fields.Length || !p.EnumerateObject().Select(f=>f.Name).ToHashSet().SetEquals(fields)
            || !Text(p,"schemaVersion",Schema) || !Text(p,"phase","INITIAL") || !Name(p.GetProperty("parentGroup")) || Text(p,"parentGroup","FIELD") || !Name(p.GetProperty("objectName"))
            || p.GetProperty("keyword").ValueKind!=JsonValueKind.String || p.GetProperty("keyword").GetString() is not ("GCONPROD" or "WGRUPCON" or "GEFAC")
            || p.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number || !p.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline<=0
            || p.GetProperty("lineNumber").ValueKind!=JsonValueKind.Number || !p.GetProperty("lineNumber").TryGetInt32(out var line) || line<=0
            || p.GetProperty("sourceFile").ValueKind!=JsonValueKind.String || !Regex.IsMatch(p.GetProperty("sourceFile").GetString()!,"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || !Value(p.GetProperty("originalValue"),out var original) || !Value(p.GetProperty("targetValue"),out var target) || original==target
            || p.GetProperty("members").ValueKind!=JsonValueKind.Array) return false;
        var keyword=p.GetProperty("keyword").GetString()!;
        if(keyword=="GCONPROD" ? !Text(p,"unit","STB/day") && !Text(p,"unit","sm3/day") || !p.GetProperty("parentGroup").GetString()!.Equals(p.GetProperty("objectName").GetString())
            : !Text(p,"unit","dimensionless") || keyword=="GEFAC" && (original>1 || target>1)) return false;
        var members=p.GetProperty("members").EnumerateArray().ToArray();
        if(members.Length<4 || members.Length>1000 || members.Any(m=>!Name(m)) || members.Select(m=>m.GetString()).Distinct().Count()!=members.Length) return false;
        result=new(baseline,p.GetProperty("parentGroup").GetString()!,p.GetProperty("objectName").GetString()!,keyword,p.GetProperty("sourceFile").GetString()!,line,p.GetProperty("unit").GetString()!,original,target,members.Select(m=>m.GetString()!).ToArray());return true;
    }
    private static bool Text(JsonElement p,string key,string value)=>p.GetProperty(key).ValueKind==JsonValueKind.String && p.GetProperty(key).GetString()==value;
    private static bool Name(JsonElement p)=>p.ValueKind==JsonValueKind.String && Regex.IsMatch(p.GetString()!,"^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$");
    private static bool Value(JsonElement p,out double value){value=0;return p.ValueKind==JsonValueKind.Number && p.TryGetDouble(out value) && double.IsFinite(value) && value>0 && value<=100_000_000;}
}
