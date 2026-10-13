using System.Net;

namespace Grdp.SoftwareIntegration.Worker.EnvironmentSetup;

public static class EnvironmentEndpoints
{
    public static void MapSimulatorEnvironment(this WebApplication app)
    {
        app.Services.GetRequiredService<SimulatorEnvironment>().Initialize();
        app.MapGet("/api/environment", async (SimulatorEnvironment environment, CancellationToken token)
            => Results.Ok(await environment.InspectAsync(token)));
        app.MapPut("/api/environment", async (HttpContext context, EnvironmentChange change,
            SimulatorEnvironment environment, CancellationToken token) =>
        {
            // Only our host-local backend can change executable paths. Browser cross-origin
            // requests cannot supply the required custom header without a CORS preflight.
            if (context.Connection.RemoteIpAddress is not { } address || !IPAddress.IsLoopback(address)
                || context.Request.Headers.ContainsKey("Origin") || context.Request.Headers["X-GRDP-Environment"] != "1")
                return Results.Json(new { message = "请在计算主机本地配置环境。" }, statusCode: 403);
            try { return Results.Ok(await environment.SaveAsync(change, token)); }
            catch (ArgumentException ex) { return Results.BadRequest(new { message = ex.Message }); }
            catch (InvalidOperationException ex) { return Results.Conflict(new { message = ex.Message }); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            { return Results.Json(new { message = "无法保存本机配置，请检查计算服务的数据目录权限。" }, statusCode: 500); }
        });
    }
}
