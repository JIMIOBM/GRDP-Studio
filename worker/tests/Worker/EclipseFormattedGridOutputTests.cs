using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Grdp.SoftwareIntegration.Worker.Contracts;
using Microsoft.Extensions.Options;
namespace Grdp.SoftwareIntegration.Worker.Tests;
public sealed class EclipseFormattedGridOutputTests
{
    [Theory][InlineData("FINIT")][InlineData("FEGRID")]
    public async Task ActualNativeFormattedGridBytesPublishUnchangedAndRemainOutsideBinaryFieldIndex(string extension)
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-fgrid",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var input=Path.Combine(root,"CASE."+extension);var output=Path.Combine(root,"jobs","1","output");Directory.CreateDirectory(output);
            var bytes=await File.ReadAllBytesAsync(Path.Combine(AppContext.BaseDirectory,"Fixtures","PVDO."+extension),TestContext.Current.CancellationToken);await File.WriteAllBytesAsync(input,bytes,TestContext.Current.CancellationToken);
            Assert.True(EclipseRunRules.IsFormattedGridOutput(input));Assert.False(EclipseRunRules.IsFieldBinaryOutput(input));Assert.False(EclipseRunRules.IsApprovedBinaryOutput(input));
            var store=new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions{StorageRoot=root})));
            var descriptor=await store.CopyEclipseFormattedGridOutputAsync(output,input,TestContext.Current.CancellationToken);Assert.Equal("application/octet-stream",descriptor.ContentType);Assert.Equal(bytes,await File.ReadAllBytesAsync(Path.Combine(output,"eclipse-output-CASE."+extension),TestContext.Current.CancellationToken));
        }finally{Directory.Delete(root,true);}
    }
    [Theory]
    [InlineData(" 'PORO    '           2 'REAL'\n .2\n")]
    [InlineData(" 'PORO    '           1 'REAL'\n NaN\n")]
    [InlineData(" 'GRIDUNIT'           1 'CHAR'\n 'C:\\host'\n")]
    [InlineData(" 'PORO    '           1 'REAL'\n .2\n private host information\n")]
    public async Task MalformedCountsNonfiniteValuesAndPrivateTextFailBeforePublication(string text)
    {
        var root=Path.Combine(Path.GetTempPath(),"grdp-fgrid-bad",Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try {
            var input=Path.Combine(root,"CASE.FINIT");var output=Path.Combine(root,"jobs","1","output");Directory.CreateDirectory(output);await File.WriteAllTextAsync(input,text,TestContext.Current.CancellationToken);
            var store=new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions{StorageRoot=root})));await Assert.ThrowsAsync<InvalidDataException>(()=>store.CopyEclipseFormattedGridOutputAsync(output,input,TestContext.Current.CancellationToken));Assert.Empty(Directory.GetFiles(output));
        }finally{Directory.Delete(root,true);}
    }
}
