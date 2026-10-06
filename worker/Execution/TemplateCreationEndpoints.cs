using System.Security.Cryptography;
using Grdp.SoftwareIntegration.Worker.Contracts;

namespace Grdp.SoftwareIntegration.Worker.Execution;

public static class TemplateCreationEndpoints
{
    public static IServiceCollection AddTemplateCreation(this IServiceCollection services) => services
        .AddSingleton<TemplateCreationStore>().AddSingleton<ITemplateCreationAdapter, TemplateCreationAdapter>().AddSingleton<TemplateCreationService>();

    public static void MapTemplateCreation(this WebApplication app)
    {
        // Loopback Worker API only. Spring project authorization/version registration comes next.
        app.MapGet("/api/model-creations/pipesim-template/capabilities", (TemplateCreationService service) => Results.Ok(service.Capabilities()));
        app.MapPost("/api/model-creations/{id:guid}/cancel", (Guid id, TemplateCreationService service) =>
        {
            var outcome = service.Cancel(id);
            return Results.Json(outcome.Body, statusCode: outcome.HttpStatus);
        });
        app.MapPost("/api/model-creations/pipesim-template", async (TemplateCreationRequest request, TemplateCreationService service, CancellationToken token) =>
        {
            var outcome = await service.CreateAsync(request, token);
            return Results.Json(outcome.Body, statusCode: outcome.HttpStatus);
        });
        app.MapGet("/api/model-creations/{id:guid}", (Guid id, TemplateCreationStore store) =>
        {
            if (id == Guid.Empty) return Results.BadRequest(WorkerApiError.Request("INVALID_CREATION_ID", "Creation ID is required."));
            var record = store.Read(id);
            return record is null ? Results.NotFound() : Results.Ok(record);
        });
        app.MapGet("/api/model-creations/{id:guid}/model", async (Guid id, TemplateCreationStore store, CancellationToken token) =>
        {
            if (id == Guid.Empty) return Results.BadRequest();
            var record = store.Read(id);
            if (record?.Status != "SUCCEEDED" || record.Model is null) return Results.NotFound();
            var stream = File.OpenRead(store.ModelPath(id));
            try
            {
                if (stream.Length != record.Model.SizeBytes || Convert.ToHexString(await SHA256.HashDataAsync(stream, token)).ToLowerInvariant() != record.Model.Sha256)
                {
                    await stream.DisposeAsync();
                    return Results.Conflict(WorkerApiError.Storage("CREATED_MODEL_CHANGED", "Created model no longer matches its immutable record."));
                }
                stream.Position = 0;
                return Results.Stream(stream, "application/octet-stream", fileDownloadName: record.Model.Name);
            }
            catch { await stream.DisposeAsync(); throw; }
        });
    }
}
