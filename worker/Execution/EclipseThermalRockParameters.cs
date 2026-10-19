using System.Text.Json;
using System.Text.RegularExpressions;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseThermalRockParameters(long BaselineRunId,string SourceFile,string SourceSha256,string Field,
    int LineNumber,int CellCount,string Unit,double OriginalValue,double TargetValue)
{
    internal const string Schema="eclipse-thermal-rock-parameters/1";
    internal static bool Value(string field,double v)=>double.IsFinite(v)&&v<=1_000_000_000&&(field=="HEATCR"?v>0:field=="THCONR"&&v>=0);
    internal static bool UnitMatches(string field,string unit)=>field=="HEATCR"?unit is "Btu/ft3/degR" or "kJ/m3/K":field=="THCONR"&&unit is "Btu/ft/day/degR" or "kJ/m/day/K";
    private static bool Text(JsonElement n,string key,string pattern)=>n.GetProperty(key).ValueKind==JsonValueKind.String&&Regex.IsMatch(n.GetProperty(key).GetString()!,pattern,RegexOptions.CultureInvariant);
    private static bool Integer(JsonElement n,string key,long max)=>n.GetProperty(key).ValueKind==JsonValueKind.Number&&n.GetProperty(key).TryGetInt64(out var v)&&v>=1&&v<=max;
    internal static bool TryParse(JsonElement n,out EclipseThermalRockParameters? result)
    {
        result=null;string[] fields=["schemaVersion","baselineRunId","sourceFile","sourceSha256","field","lineNumber","cellCount","unit","originalValue","targetValue"];
        if(n.ValueKind!=JsonValueKind.Object||n.EnumerateObject().Count()!=fields.Length||!n.EnumerateObject().Select(p=>p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(fields)
            ||!Text(n,"schemaVersion","^eclipse-thermal-rock-parameters/1$")||!Integer(n,"baselineRunId",long.MaxValue)||!Integer(n,"lineNumber",int.MaxValue)||!Integer(n,"cellCount",250000)
            ||!Text(n,"sourceFile","^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")||!Text(n,"sourceSha256","^[a-f0-9]{64}$")
            ||!Text(n,"field","^(HEATCR|THCONR)$")||n.GetProperty("unit").ValueKind!=JsonValueKind.String
            ||n.GetProperty("originalValue").ValueKind!=JsonValueKind.Number||!n.GetProperty("originalValue").TryGetDouble(out var original)
            ||n.GetProperty("targetValue").ValueKind!=JsonValueKind.Number||!n.GetProperty("targetValue").TryGetDouble(out var target))return false;
        var field=n.GetProperty("field").GetString()!;var unit=n.GetProperty("unit").GetString()!;
        if(!UnitMatches(field,unit)||!Value(field,original)||!Value(field,target)||original==target)return false;
        result=new(n.GetProperty("baselineRunId").GetInt64(),n.GetProperty("sourceFile").GetString()!,n.GetProperty("sourceSha256").GetString()!,field,
            n.GetProperty("lineNumber").GetInt32(),n.GetProperty("cellCount").GetInt32(),unit,original,target);return true;
    }
}
