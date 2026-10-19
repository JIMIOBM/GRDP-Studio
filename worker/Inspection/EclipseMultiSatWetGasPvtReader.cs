using Grdp.SoftwareIntegration.Worker.Contracts;
namespace Grdp.SoftwareIntegration.Worker.Inspection;
internal sealed record EclipseMultiSatWetGasPvtContexts(EclipseMultiSatRelpermContext Sat,EclipseMultiWetGasPvtContext Pvt);
internal static class EclipseMultiSatWetGasPvtReader
{
    internal static EclipseMultiSatWetGasPvtContexts? Read(string text,string sourceFile){
        var pvt=EclipseMultiWetGasPvtReader.ReadCombined(text,sourceFile);if(pvt is null)return null;
        var sat=EclipseMultiSatRelpermReader.ReadFamilyIICombinedWetGas(text,sourceFile) ?? EclipseMultiSatRelpermReader.ReadCombinedWetGas(text,sourceFile);
        return sat is null?null:new(sat,pvt);
    }
}
