namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class EclipseRestartReader
{
    public static bool IsBinary(EclipseRestartCatalog? catalog) => catalog is not null && catalog.SchemaVersion == "eclipse-restart-catalog/2"
        && catalog.ArtifactName == "eclipse-output-" + catalog.CaseName + ".UNRST";
    public static EclipseRestartCatalog Read(string path) => Path.GetExtension(path).Equals(".UNRST", StringComparison.OrdinalIgnoreCase)
        ? EclipseBinaryRestartReader.Read(path) : EclipseFormattedRestartReader.Read(path);
    public static byte[] Select(string path, EclipseRestartCatalog catalog, int report) => IsBinary(catalog)
        ? EclipseBinaryRestartReader.Select(path, catalog, report) : EclipseFormattedRestartReader.Select(path, catalog, report);
}
