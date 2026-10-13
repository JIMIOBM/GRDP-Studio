using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

internal sealed record EclipseNewCompletionParameters(long BaselineRunId,string SourceFile,string SourceSha256,string GridFile,string GridArtifactSha256,
    int Nx,int Ny,int Nz,int InsertionLine,string Well,int I,int J,int K,double Diameter,double Kh,double Skin,string LengthUnit)
{
    internal const string Schema="eclipse-new-completion-parameters/1";
    internal static bool TryParse(JsonElement value,out EclipseNewCompletionParameters? p)
    {
        p=null;
        string[] fields=["schemaVersion","baselineRunId","sourceFile","sourceSha256","gridFile","gridArtifactSha256","nx","ny","nz","insertionLine","well","i","j","k","diameter","kh","skin","lengthUnit"];
        if(value.ValueKind!=JsonValueKind.Object||value.EnumerateObject().Count()!=fields.Length||!value.EnumerateObject().Select(v=>v.Name).ToHashSet(StringComparer.Ordinal).SetEquals(fields))return false;
        string Text(string key)=>value.GetProperty(key).ValueKind==JsonValueKind.String?value.GetProperty(key).GetString()!:"";
        if(Text("schemaVersion")!=Schema||value.GetProperty("baselineRunId").ValueKind!=JsonValueKind.Number||!value.GetProperty("baselineRunId").TryGetInt64(out var baseline)||baseline<=0
            ||!Regex.IsMatch(Text("sourceFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)
            ||!Regex.IsMatch(Text("gridFile"),@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.EGRID$",RegexOptions.IgnoreCase|RegexOptions.CultureInvariant)
            ||new[]{"sourceSha256","gridArtifactSha256"}.Any(k=>!Regex.IsMatch(Text(k),"^[0-9a-f]{64}$",RegexOptions.CultureInvariant))
            ||!Regex.IsMatch(Text("well"),"^[A-Za-z][A-Za-z0-9_-]{0,7}$",RegexOptions.CultureInvariant))return false;
        int[] integers=new int[7];string[] integerKeys=["nx","ny","nz","insertionLine","i","j","k"];
        for(int n=0;n<integerKeys.Length;n++){var v=value.GetProperty(integerKeys[n]);if(v.ValueKind!=JsonValueKind.Number||!v.TryGetInt32(out integers[n])||integers[n] is <=0 or >2_000_000)return false;}
        if((long)integers[0]*integers[1]*integers[2]>2_000_000||integers[4]>integers[0]||integers[5]>integers[1]||integers[6]>integers[2])return false;
        double[] numbers=new double[3];string[] numberKeys=["diameter","kh","skin"];
        for(int n=0;n<numberKeys.Length;n++){var v=value.GetProperty(numberKeys[n]);if(v.ValueKind!=JsonValueKind.Number||!v.TryGetDouble(out numbers[n])||!double.IsFinite(numbers[n]))return false;}
        string length=Text("lengthUnit");
        if(!new[]{"m","ft","cm"}.Contains(length)||!EclipseCompletionGeometryParameters.ValidScalar("DIAMETER",length,numbers[0])
            ||!EclipseCompletionGeometryParameters.ValidScalar("KH","mD."+length,numbers[1])||!EclipseCompletionGeometryParameters.ValidScalar("SKIN","1",numbers[2]))return false;
        p=new(baseline,Text("sourceFile"),Text("sourceSha256"),Text("gridFile"),Text("gridArtifactSha256"),integers[0],integers[1],integers[2],integers[3],Text("well"),integers[4],integers[5],integers[6],numbers[0],numbers[1],numbers[2],length);
        return true;
    }
    internal bool MatchesInspection(EclipseDataInspection inspection)
    {
        var c=inspection.NewWellContext;var d=inspection.Dimensions;var metadata=inspection.ScheduleMetadata;
        if(c is null||c.SchemaVersion!="eclipse-new-well-context/1"||c.SourceFile!=SourceFile||c.InsertionLine!=InsertionLine||d is null||metadata is null
            ||(d.Nx,d.Ny,d.Nz)!=(Nx,Ny,Nz)||inspection.Phases.Count!=1||inspection.Phases[0]!="WATER"
            ||EclipseNewWellParameters.Units(inspection.UnitSystem).Length!=LengthUnit||metadata.Groups.Count>0||c.MaxConnections<1
            ||metadata.Wells.Count(w=>w.Name==Well)!=1||metadata.Wells.Any(w=>w.SourceFile!=SourceFile||w.LineNumber is null||w.LineNumber>=InsertionLine))return false;
        var controls=metadata.Records.Where(r=>r.Keyword=="WCONPROD"&&r.Values.Count>=5&&r.Values[0].Trim()==Well).ToArray();
        if(controls.Length!=1||controls[0].Values[1]!="OPEN"||controls[0].Values[2]!="WRAT"
            ||!double.TryParse(controls[0].Values[4],NumberStyles.Float,CultureInfo.InvariantCulture,out double rate)||!double.IsFinite(rate)||rate<=0)return false;
        if(metadata.Records.Any(r=>r.SourceFile!=SourceFile||r.LineNumber is null||r.LineNumber>=InsertionLine||r.Values.Count==0||r.Values[0].Contains('*')||r.Values[0].Contains('?')))return false;
        long selectedConnections=0;
        foreach(var r in metadata.Completions){
            if(r.Keyword!="COMPDAT"||r.SourceFile!=SourceFile||r.LineNumber<=0||r.LineNumber>=InsertionLine
                ||!Coordinate(r.I,out int i)||!Coordinate(r.J,out int j)||!Coordinate(r.K1,out int first)||!Coordinate(r.K2,out int last)
                ||i>Nx||j>Ny||last>Nz||last<first||i==I&&j==J&&K>=first&&K<=last)return false;
            if(r.Well.Trim()==Well)selectedConnections+=(long)last-first+1;
        }
        return selectedConnections>0&&selectedConnections+1<=c.MaxConnections;
    }
    private static bool Coordinate(string text,out int value){value=0;return Regex.IsMatch(text,"^[1-9][0-9]*$",RegexOptions.CultureInvariant)&&int.TryParse(text,NumberStyles.None,CultureInfo.InvariantCulture,out value);}
}
