using System.Text.Json;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Inspection;
namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class EclipseGroupStructureTests : IDisposable
{
    private readonly string root=Path.Combine(Path.GetTempPath(),"grdp-group-structure",Guid.NewGuid().ToString("N"));
    public EclipseGroupStructureTests()=>Directory.CreateDirectory(root);
    public void Dispose()=>Directory.Delete(root,true);
    private static string Sample(string name)=>File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"Fixtures",$"ECLIPSE_GROUP_{name}.DATA"));
    [Theory][InlineData("TREE","GCONPROD","P",1000,1)]
    [InlineData("GUIDE","WGRUPCON","PRODUCR2",3,5)]
    [InlineData("EFFICIENCY","GEFAC","GA",0.5,2)]
    public async Task RealReferenceGraphAndExactEditedTokenAreBound(string name,string keyword,string objectName,double value,int count)
    {
        var text=Sample(name);var path=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(path,text,TestContext.Current.CancellationToken);
        var i=EclipseDataInspector.InspectPackage([path],"CASE.DATA");Assert.Equal("eclipse-data-inspection/12",i.SchemaVersion);Assert.Null(i.GroupControls);Assert.Null(i.AdvancedInjectionGroupControls);
        Assert.Equal(count,i.GroupStructureControls!.Count);var target=Assert.Single(EclipseGroupStructureReader.Read(text,"CASE.DATA"),t=>t.Control.Keyword==keyword && t.Control.ObjectName==objectName);var c=target.Control;
        Assert.Equal("P",c.ParentGroup);Assert.Equal(2000,c.ParentTarget);Assert.Equal(new[]{"GA","GB"},c.Leaves.Select(l=>l.Group));
        Assert.Equal(new[]{"PRODUCR1","PRODUCR2","PRODUCR3","PRODUCR4"},c.Leaves.SelectMany(l=>l.Members).Select(m=>m.Well));
        Assert.All(c.Leaves.SelectMany(l=>l.Members),m=>{Assert.Equal(2500,m.BhpLimit);Assert.Equal(20000,m.OilRateLimit);});
        Assert.Equal(name=="EFFICIENCY"?0.8:1,c.Leaves[0].Efficiency);Assert.All(c.Leaves.SelectMany(l=>l.Members),m=>Assert.Equal(name=="GUIDE"?1:0,m.GuideRate));
        var p=new EclipseGroupStructureParameters(1,c.ParentGroup,c.ObjectName,c.Keyword,c.SourceFile,c.LineNumber,c.Unit,c.OriginalValue,value,c.Leaves.SelectMany(l=>l.Members).Select(m=>m.Well).ToArray());
        await EclipseGroupStructureEditor.ApplyAsync(new(path,root,[path]),p,TestContext.Current.CancellationToken);
        Assert.Equal(text[..target.Start]+value.ToString("G17",System.Globalization.CultureInfo.InvariantCulture)+text[(target.Start+target.Length)..],await File.ReadAllTextAsync(path,TestContext.Current.CancellationToken));
    }
    [Theory][InlineData("cycle")][InlineData("parentWell")][InlineData("mixedParents")][InlineData("missingLeafControl")][InlineData("shut")][InlineData("mixedControls")][InlineData("waterLimit")][InlineData("dynamic")][InlineData("nested")][InlineData("economic")]
    public void AmbiguousTreeOrExtraWellControlsAreNotEditable(string fault)
    {
        var s=Sample("TREE");s=fault switch {
            "cycle"=>s.Replace("'GA' 'P' /","'GA' 'GA' /"),
            "parentWell"=>s.Replace("'PRODUCR1' 'GA'","'PRODUCR1' 'P'"),
            "mixedParents"=>s.Replace("'GB' 'P' /","'GB' 'OTHER' /"),
            "missingLeafControl"=>s.Replace(" 'GB' 'FLD' /", ""),
            "shut"=>s.Replace("'PRODUCR1' 'OPEN'", "'PRODUCR1' 'SHUT'"),
            "mixedControls"=>s.Replace("'PRODUCR1' 'OPEN' 'GRUP'", "'PRODUCR1' 'OPEN' 'ORAT'"),
            "waterLimit"=>s.Replace("'GRUP' 20000  4*", "'GRUP' 20000  100 3*"),
            "nested"=>s.Replace("'GB' 'P' /", "'GB' 'GA' /"),
            "economic"=>s.Replace("TSTEP", "CECON\n PRODUCR1 1 /\n/\nTSTEP"),
            _=>s.Replace("END     =", "GEFAC\n GA 0.5 /\n/\nEND     =")};
        Assert.Empty(EclipseGroupStructureReader.Read(s,"CASE.DATA"));
    }
    [Theory][InlineData("missingGuide")][InlineData("phase")][InlineData("scale")][InlineData("potential")][InlineData("mixedEfficiency")][InlineData("formula")]
    public void FixedGuidesRequireCompleteOilWeightsAndCannotMixEfficiencyOrFormula(string fault)
    {
        var s=Sample("GUIDE");s=fault switch {
            "missingGuide"=>s.Replace(" 'PRODUCR4' 'YES' 1 'OIL' /", ""),
            "phase"=>s.Replace("'YES' 1 'OIL' /", "'YES' 1 'GAS' /"),
            "scale"=>s.Replace("'YES' 1 'OIL' /", "'YES' 1 'OIL' 2 /"),
            "potential"=>s.Replace("'YES' 1 'OIL' /", "'YES' 0 'OIL' /"),
            "mixedEfficiency"=>s.Replace("TSTEP", "GEFAC\n GA 0.8 /\n/\nTSTEP"),
            _=>s.Replace("TSTEP", "GUIDERAT\n 0 OIL 1 1 /\nTSTEP")};
        Assert.Empty(EclipseGroupStructureReader.Read(s,"CASE.DATA"));
    }
    [Theory][InlineData("field")][InlineData("node")][InlineData("greaterThanOne")][InlineData("duplicate")]
    public void EfficiencyOnlyRefersToOneLiteralLeafDowntimeFactor(string fault)
    {
        var s=Sample("EFFICIENCY");s=fault switch {"field"=>s.Replace("'GA' 0.8 /","'FIELD' 0.8 /"),"node"=>s.Replace("'GA' 0.8 /","'P' 0.8 /"),"greaterThanOne"=>s.Replace("'GA' 0.8 /","'GA' 1.2 /"),_=>s.Replace("'GA' 0.8 /","'GA' 0.8 /\n 'GA' 0.5 /")};
        Assert.Empty(EclipseGroupStructureReader.Read(s,"CASE.DATA"));
    }
    [Fact]public void ParameterContractRejectsUnknownFieldsMissingGraphAndInvalidEfficiency()
    {
        const string json="""{"schemaVersion":"eclipse-group-structure-parameters/1","baselineRunId":1,"phase":"INITIAL","parentGroup":"P","objectName":"GA","keyword":"GEFAC","sourceFile":"CASE.DATA","lineNumber":100,"unit":"dimensionless","originalValue":0.8,"targetValue":0.5,"members":["P1","P2","P3","P4"]}""";
        using var good=JsonDocument.Parse(json);Assert.True(EclipseGroupStructureParameters.TryParse(good.RootElement,out _));
        foreach(var bad in new[]{json.Replace("0.5","1.2"),json.Replace("dimensionless","STB/day"),json.Replace("\"P4\"","\"P3\""),json.Replace("\"lineNumber\":100","\"extra\":0,\"lineNumber\":100")})
        {using var d=JsonDocument.Parse(bad);Assert.False(EclipseGroupStructureParameters.TryParse(d.RootElement,out _));}
    }
    [Theory][InlineData("source")][InlineData("line")][InlineData("parent")][InlineData("unit")][InlineData("original")][InlineData("members")][InlineData("object")]
    public async Task SourceGraphOrOriginalValueMismatchNeverChangesInput(string fault)
    {
        var text=Sample("GUIDE");var path=Path.Combine(root,"CASE.DATA");await File.WriteAllTextAsync(path,text,TestContext.Current.CancellationToken);
        var c=Assert.Single(EclipseGroupStructureReader.Read(text,"CASE.DATA"),t=>t.Control.ObjectName=="PRODUCR2").Control;
        var p=new EclipseGroupStructureParameters(1,c.ParentGroup,c.ObjectName,c.Keyword,c.SourceFile,c.LineNumber,c.Unit,c.OriginalValue,3,c.Leaves.SelectMany(l=>l.Members).Select(m=>m.Well).ToArray());
        p=fault switch {"source"=>p with{SourceFile="OTHER.DATA"},"line"=>p with{LineNumber=c.LineNumber+1},"parent"=>p with{ParentGroup="OTHER"},"unit"=>p with{Unit="STB/day"},"original"=>p with{OriginalValue=2},"members"=>p with{Members=p.Members.Reverse().ToArray()},_=>p with{ObjectName="WRONG"}};
        await Assert.ThrowsAsync<EclipseDeckPackageException>(()=>EclipseGroupStructureEditor.ApplyAsync(new(path,root,[path]),p,TestContext.Current.CancellationToken));
        Assert.Equal(text,await File.ReadAllTextAsync(path,TestContext.Current.CancellationToken));
    }
}
