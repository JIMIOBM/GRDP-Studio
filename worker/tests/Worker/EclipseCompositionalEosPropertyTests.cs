using System.Text.Json;
using System.Text.Json.Nodes;
using Grdp.SoftwareIntegration.Worker.Execution;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseCompositionalEosPropertyTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-compositional-eos-property", Guid.NewGuid().ToString("N"));
    public EclipseCompositionalEosPropertyTests() => Directory.CreateDirectory(root);
    public void Dispose() => Directory.Delete(root, true);
    private EclipseDeckPackage Package() {
        var file=Path.Combine(root,"SPE3_GASCOND.DATA"); File.Copy(Path.Combine(AppContext.BaseDirectory,"Fixtures/COMPOSITIONAL-TARGET-BASE.DATA"),file);
        return new EclipseDeckPackageResolver().Resolve(file,root);
    }
    private static EclipseCompositionalFluidPropertyParameters Parameters(EclipseCompositionalFluidPropertyReader.Snapshot s,double value) {
        var r=s.Context.Records[2];return new(1,s.Context.SourceFile,s.Context.SourceSha256,r.LineNumber,r.Field,3,r.ComponentName,r.Unit,r.OriginalValue,value);
    }
    [Theory]
    [InlineData("MW",17.0,140,"lb/lb-M",16.043)]
    [InlineData("OMEGAA",0.54,146,"dimensionless",0.534021)]
    [InlineData("OMEGAB",0.078,152,"dimensionless",0.0777961)]
    public async Task OnlySelectedComponentChangesAndMatchesTheIndependentNativeInput(string field,double value,int line,string unit,double original) {
        var package=Package();var s=EclipseCompositionalFluidPropertyReader.Read(package,field);Assert.NotNull(s);
        Assert.Equal(9,s.Context.Records.Count);Assert.Equal("C1",s.Context.Records[2].ComponentName);Assert.Equal(line,s.Context.Records[2].LineNumber);
        Assert.Equal(unit,s.Context.Records[2].Unit);Assert.Equal(original,s.Context.Records[2].OriginalValue);
        var all=new[]{"PCRIT","TCRIT","ZCRIT","ACF","MW","OMEGAA","OMEGAB"}.ToDictionary(f=>f,f=>EclipseCompositionalFluidPropertyReader.Read(package,f)!.Context.Records);
        await EclipseCompositionalFluidPropertyEditor.ApplyAsync(package,Parameters(s,value),TestContext.Current.CancellationToken);
        Assert.Equal(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory,$"Fixtures/COMPOSITIONAL-EOS-{field}.DATA")),File.ReadAllBytes(package.MainFile));
        foreach(var f in all.Keys) Assert.Equal(all[f].Select(r=>f==field&&r.ComponentIndex==3?r with{OriginalValue=value}:r),EclipseCompositionalFluidPropertyReader.Read(package,f)!.Context.Records);
    }
    [Theory] [InlineData("MW",17.0)] [InlineData("OMEGAA",0.54)] [InlineData("OMEGAB",0.078)]
    public void EffectiveOverridesRequireTheCorrectFieldAndUnitWithStrictNumericValues(string field,double value) {
        var s=EclipseCompositionalFluidPropertyReader.Read(Package(),field);Assert.NotNull(s);var p=Parameters(s,value);
        var j=new JsonObject{["schemaVersion"]=EclipseCompositionalFluidPropertyParameters.EosPropertySchema,["baselineRunId"]=1,["sourceFile"]=p.SourceFile,["sourceSha256"]=p.SourceSha256,["lineNumber"]=p.LineNumber,["field"]=field,["componentIndex"]=3,["componentName"]="C1",["unit"]=p.Unit,["originalValue"]=p.OriginalValue,["targetValue"]=value};
        Assert.True(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(j),out var parsed));Assert.Equal(p,parsed);
        foreach(var (key,v) in new (string,JsonNode?)[]{("targetValue",JsonValue.Create(0)),("targetValue",JsonValue.Create(-1)),("targetValue",JsonValue.Create(1e9)),("targetValue",JsonValue.Create(p.OriginalValue)),("targetValue",JsonValue.Create("17")),("field",JsonValue.Create("ACF")),("schemaVersion",JsonValue.Create(EclipseCompositionalFluidPropertyParameters.AcentricFactorSchema)),("unit",JsonValue.Create(field=="MW"?"dimensionless":"lb/lb-M")),("other",JsonValue.Create(1))}) {
            var bad=(JsonObject)j.DeepClone();bad[key]=v;Assert.False(EclipseCompositionalFluidPropertyParameters.TryParse(JsonSerializer.SerializeToElement(bad),out _));
        }
    }
    [Theory]
    [InlineData("MW","16.04300")] [InlineData("OMEGAA",".5340210")] [InlineData("OMEGAB",".0777961")]
    public void DefaultsComputedOrLumpedAndIneffectiveValuesDoNotOfferAPropertyEditor(string field,string token) {
        var package=Package();var source=File.ReadAllText(package.MainFile);
        foreach(var changed in new[]{source.Replace(token,"1*"),source.Replace(token,"0"),source.Replace(token,"-1"),source.Replace(token,"NaN"),source.Replace(token,""),source.Replace(token,token+" 1"),source.Replace("\r\nFIELD","\r\nLUMPDIMS\r\nFIELD"),source.Replace("EQUALS\r\nPORO",$"EQUALS\r\n{field} 1 /\r\nPORO")}) {
            Assert.NotEqual(source,changed);File.WriteAllText(package.MainFile,changed);Assert.Null(EclipseCompositionalFluidPropertyReader.Read(package,field));
        }
    }
}
