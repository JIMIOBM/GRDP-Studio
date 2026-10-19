using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal sealed record EclipseMultiSatPvtContexts(EclipseMultiSatRelpermContext Sat,EclipseMultiPvtContext Pvt);
internal static class EclipseMultiSatPvtReader
{
    internal static EclipseMultiSatPvtContexts? Read(string text,string sourceFile){
        var pvt=EclipseMultiPvtReader.ReadCombined(text,sourceFile);if(pvt is null)return null;
        var sat=EclipseMultiSatRelpermReader.ReadFamilyIICombined(text,sourceFile) ?? EclipseMultiSatRelpermReader.ReadCombined(text,sourceFile);
        return sat is null?null:new(sat,pvt);
    }
}
