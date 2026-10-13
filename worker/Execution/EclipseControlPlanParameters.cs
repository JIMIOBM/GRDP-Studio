using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseControlPlanEdit(string Well,string SourceFile,int LineNumber,string Date,int TimeDays,string Unit,double OriginalOilRate,double TargetOilRate);
internal sealed record EclipseControlPlanParameters(long BaselineRunId,IReadOnlyList<EclipseControlPlanEdit> Edits)
{
    internal const string Schema="eclipse-control-plan-parameters/1";
    internal static bool TryParse(JsonElement p,out EclipseControlPlanParameters? result)
    {
        result=null;
        if(!Shape(p,["schemaVersion","baselineRunId","edits"]) || !Text(p,"schemaVersion",Schema)
            || p.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number || !p.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline<=0
            || p.GetProperty("edits").ValueKind!=JsonValueKind.Array || p.GetProperty("edits").GetArrayLength() is <1 or >24)return false;
        var edits=new List<EclipseControlPlanEdit>();var seen=new HashSet<(string,string)>();var previousLine=0;
        foreach(var e in p.GetProperty("edits").EnumerateArray())
        {
            if(!Shape(e,["well","sourceFile","lineNumber","date","timeDays","unit","originalOilRate","targetOilRate"])
                || !String(e,"well",out var well) || !Regex.IsMatch(well,"^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$")
                || !String(e,"sourceFile",out var source) || !Regex.IsMatch(source,"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\\.[Dd][Aa][Tt][Aa]$")
                || !String(e,"date",out var date) || !DateOnly.TryParseExact(date,"yyyy-MM-dd",CultureInfo.InvariantCulture,DateTimeStyles.None,out _)
                || !Int(e,"lineNumber",out var line) || line<=previousLine || !Int(e,"timeDays",out var time) || time<0
                || !String(e,"unit",out var unit) || unit is not ("STB/day" or "sm3/day")
                || !Positive(e.GetProperty("originalOilRate"),out var original) || !Positive(e.GetProperty("targetOilRate"),out var target) || original==target
                || !seen.Add((well,date)) || edits.Count>0 && source!=edits[0].SourceFile)return false;
            edits.Add(new(well,source,line,date,time,unit,original,target));previousLine=line;
        }
        result=new(baseline,edits);return true;
    }
    private static bool Shape(JsonElement p,string[] fields)=>p.ValueKind==JsonValueKind.Object && p.EnumerateObject().Count()==fields.Length && p.EnumerateObject().Select(f=>f.Name).ToHashSet().SetEquals(fields);
    private static bool String(JsonElement p,string field,out string result){result="";if(p.GetProperty(field).ValueKind!=JsonValueKind.String)return false;result=p.GetProperty(field).GetString()!;return true;}
    private static bool Text(JsonElement p,string field,string text)=>String(p,field,out var s) && s==text;
    private static bool Int(JsonElement p,string field,out int n){n=0;return p.GetProperty(field).ValueKind==JsonValueKind.Number && p.GetProperty(field).TryGetInt32(out n);}
    private static bool Positive(JsonElement p,out double n){n=0;return p.ValueKind==JsonValueKind.Number && p.TryGetDouble(out n) && double.IsFinite(n) && n>0 && n<=100_000_000;}
}
