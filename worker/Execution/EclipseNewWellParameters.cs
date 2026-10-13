using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseNewWellParameters(long BaselineRunId, string SourceFile, string SourceSha256, string GridFile, string GridArtifactSha256,
    int Nx,int Ny,int Nz,int InsertionLine,string Well,string Group,int I,int J,int K,double ReferenceDepth,string LengthUnit,
    double Diameter,double Kh,double Skin,double WaterRate,string RateUnit,double BhpLimit,string PressureUnit)
{
    internal const string Schema="eclipse-new-well-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseNewWellParameters? p)
    {
        p=null;
        string[] names=["schemaVersion","baselineRunId","sourceFile","sourceSha256","gridFile","gridArtifactSha256","nx","ny","nz","insertionLine","well","group","i","j","k","referenceDepth","lengthUnit","diameter","kh","skin","waterRate","rateUnit","bhpLimit","pressureUnit"];
        if(value.ValueKind!=JsonValueKind.Object || value.EnumerateObject().Count()!=names.Length || !value.EnumerateObject().Select(x=>x.Name).ToHashSet(StringComparer.Ordinal).SetEquals(names))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        if(Text("schemaVersion")!=Schema || value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number || !value.GetProperty("baselineRunId").TryGetInt64(out var baseline) || baseline<=0)return false;
        if(!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)
            || !Regex.IsMatch(Text("gridFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.EGRID$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)
            || new[]{"sourceSha256","gridArtifactSha256"}.Any(k=>!Regex.IsMatch(Text(k),"^[0-9a-f]{64}$",RegexOptions.CultureInvariant))
            || new[]{"well","group"}.Any(k=>!Regex.IsMatch(Text(k),"^[A-Za-z][A-Za-z0-9_-]{0,7}$",RegexOptions.CultureInvariant))
            || Text("group").Equals("FIELD",StringComparison.OrdinalIgnoreCase))return false;
        int[] ints=new int[7];string[] keys=["nx","ny","nz","insertionLine","i","j","k"];
        for(var n=0;n<keys.Length;n++){var v=value.GetProperty(keys[n]);if(v.ValueKind!=JsonValueKind.Number||!v.TryGetInt32(out ints[n])||ints[n] is <=0 or >2_000_000)return false;}
        if((long)ints[0]*ints[1]*ints[2]>2_000_000 || ints[4]>ints[0] || ints[5]>ints[1] || ints[6]>ints[2])return false;
        double[] numbers=new double[6];string[] numerical=["referenceDepth","diameter","kh","skin","waterRate","bhpLimit"];
        for(var n=0;n<numerical.Length;n++){var v=value.GetProperty(numerical[n]);if(v.ValueKind!=JsonValueKind.Number||!v.TryGetDouble(out numbers[n])||!double.IsFinite(numbers[n]))return false;}
        var length=Text("lengthUnit");var rate=Text("rateUnit");var pressure=Text("pressureUnit");
        if(!new[]{"METRIC","FIELD","LAB","PVT-M"}.Any(s=>Units(s)==(length,rate,pressure))
            || numbers[0] is <=0 or >10_000_000 || !EclipseCompletionGeometryParameters.ValidScalar("DIAMETER",length,numbers[1])
            || !EclipseCompletionGeometryParameters.ValidScalar("KH","mD."+length,numbers[2]) || !EclipseCompletionGeometryParameters.ValidScalar("SKIN","1",numbers[3])
            || numbers[4] is <=0 or >10_000_000 || numbers[5] is <=0 or >1_000_000)return false;
        p=new(baseline,Text("sourceFile"),Text("sourceSha256"),Text("gridFile"),Text("gridArtifactSha256"),ints[0],ints[1],ints[2],ints[3],Text("well"),Text("group"),ints[4],ints[5],ints[6],numbers[0],length,numbers[1],numbers[2],numbers[3],numbers[4],rate,numbers[5],pressure);
        return true;
    }
    internal static (string Length,string Rate,string Pressure) Units(string? system)=>system switch {
        "METRIC"=>("m","SM3/DAY","BARSA"),"FIELD"=>("ft","STB/DAY","PSIA"),"LAB"=>("cm","SCC/HR","ATMA"),"PVT-M"=>("m","SM3/DAY","ATMA"),_=>("","","") };
    internal bool MatchesInspection(EclipseDataInspection inspection)
    {
        var c=inspection.NewWellContext;var d=inspection.Dimensions;var m=inspection.ScheduleMetadata;
        if(c is null||c.SchemaVersion!="eclipse-new-well-context/1"||c.SourceFile!=SourceFile||c.InsertionLine!=InsertionLine||d is null||m is null
            || (d.Nx,d.Ny,d.Nz)!=(Nx,Ny,Nz)||inspection.Phases.Count!=1||inspection.Phases[0]!="WATER"||Units(inspection.UnitSystem)!=(LengthUnit,RateUnit,PressureUnit)
            || m.Groups.Count>0||m.Wells.Count==0||m.Wells.Any(w=>w.Name.Equals(Well,StringComparison.OrdinalIgnoreCase)||string.IsNullOrEmpty(w.Group)||w.SourceFile!=SourceFile||w.LineNumber is null||w.LineNumber>=InsertionLine)
            || !m.Wells.Any(w=>w.Group==Group)||m.Wells.Count+1>c.MaxWells||m.Wells.Count(w=>w.Group==Group)+1>c.MaxWellsPerGroup
            || m.Wells.Select(w=>w.Group).Distinct(StringComparer.Ordinal).Count()>c.MaxGroups||c.MaxConnections<1)return false;
        return m.Completions.All(r=>int.TryParse(r.I,out var a)&&a>0&&a<=Nx&&int.TryParse(r.J,out var b)&&b>0&&b<=Ny&&int.TryParse(r.K1,out var first)&&first>0
                &&int.TryParse(r.K2,out var last)&&last>=first&&last<=Nz)
            && m.Records.All(r=>r.SourceFile==SourceFile && r.LineNumber is not null && r.LineNumber<InsertionLine && r.Values.Count>0 && !r.Values[0].Contains('*') && !r.Values[0].Contains('?'))
            && !m.Completions.Any(r=>r.I==I.ToString(System.Globalization.CultureInfo.InvariantCulture)&&r.J==J.ToString(System.Globalization.CultureInfo.InvariantCulture)
                && int.TryParse(r.K1,out var a)&&int.TryParse(r.K2,out var b)&&K>=a&&K<=b);
    }
}
