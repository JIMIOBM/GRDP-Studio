using System.Text.Json;
using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Execution;
internal sealed record EclipseTstepParameters(long BaselineRunId,string SourceFile,int LineNumber,string Unit,IReadOnlyList<int> OriginalSteps,IReadOnlyList<int> TargetSteps)
{
    internal const string Schema="eclipse-tstep-parameters/1";
    internal static bool ValidSteps(IReadOnlyList<int> s)=>s.Count is >0 and <=1000 && s.All(v=>v>0 && v<=100000) && s.Sum(v=>(long)v)<=100000;
    internal static bool TryParse(JsonElement v,out EclipseTstepParameters? p)
    {
        p=null;string[] fields=["schemaVersion","baselineRunId","sourceFile","lineNumber","unit","originalSteps","targetSteps"];
        if(v.ValueKind!=JsonValueKind.Object || v.EnumerateObject().Count()!=fields.Length || !v.EnumerateObject().Select(x=>x.Name).ToHashSet().SetEquals(fields)
            || v.GetProperty("schemaVersion").ValueKind!=JsonValueKind.String || v.GetProperty("schemaVersion").GetString()!=Schema
            || v.GetProperty("unit").ValueKind!=JsonValueKind.String || v.GetProperty("unit").GetString()!="day"
            || v.GetProperty("sourceFile").ValueKind!=JsonValueKind.String || !Regex.IsMatch(v.GetProperty("sourceFile").GetString()!,"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
            || v.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number || !v.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline<=0
            || v.GetProperty("lineNumber").ValueKind!=JsonValueKind.Number || !v.GetProperty("lineNumber").TryGetInt32(out var line) || line<=0) return false;
        var arrays=new List<IReadOnlyList<int>>();
        foreach(var key in new[]{"originalSteps","targetSteps"}) {
            var a=v.GetProperty(key);if(a.ValueKind!=JsonValueKind.Array || a.GetArrayLength()>1000)return false;
            var values=new List<int>();foreach(var x in a.EnumerateArray()){if(x.ValueKind!=JsonValueKind.Number || !x.TryGetInt32(out var n))return false;values.Add(n);}
            if(!ValidSteps(values))return false;arrays.Add(values);
        }
        if(arrays[0].Sum()!=arrays[1].Sum() || arrays[0].SequenceEqual(arrays[1]))return false;
        p=new(baseline,v.GetProperty("sourceFile").GetString()!,line,"day",arrays[0],arrays[1]);return true;
    }
}
