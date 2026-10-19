using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal sealed record EclipseMultiSatLiveOilPvtContexts(EclipseMultiSatRelpermContext Sat,EclipseMultiLiveOilPvtContext Pvt);
internal static class EclipseMultiSatLiveOilPvtReader
{
    internal static EclipseMultiSatLiveOilPvtContexts? Read(string text,string sourceFile){
        var pvt=EclipseMultiLiveOilPvtReader.ReadCombined(text,sourceFile);if(pvt is null)return null;
        var sat=EclipseMultiSatRelpermReader.ReadFamilyIICombinedLiveOil(text,sourceFile) ?? EclipseMultiSatRelpermReader.ReadCombined(text,sourceFile);
        return sat is null?null:new(sat,pvt);
    }
}
