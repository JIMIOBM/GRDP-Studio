using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using Grdp.SoftwareIntegration.Worker.Execution;
using Grdp.SoftwareIntegration.Worker.Storage;
using Microsoft.Extensions.Options;

namespace Grdp.SoftwareIntegration.Worker.Tests;

public sealed class ArtifactStoreTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "grdp-artifact-tests", Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task RedactsSensitiveJsonKeysAndValuesBeforeWritingArtifact()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        Directory.CreateDirectory(output);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        using var document = JsonDocument.Parse("""
                {"C:\\Users\\operator\\secret":"D:\\private\\file",
                 "pipe":"net.pipe://localhost/pipe/private-id"}
                """);

        await store.WriteJsonElementAsync(output, "result.json", document.RootElement,
                TestContext.Current.CancellationToken);

        var json = await File.ReadAllTextAsync(Path.Combine(output, "result.json"),
                TestContext.Current.CancellationToken);
        Assert.DoesNotContain("operator", json);
        Assert.DoesNotContain("private-id", json);
        Assert.Contains("[redacted]", json);
    }

    [Fact]
    public async Task RedactsUncPathsRecursivelyWithoutChangingJsonStructure()
    {
        var artifact = await WriteRawJsonAsync("""{"network":{"share":"path=\\\\server\\private-share\\model.pips"}}""");

        Assert.DoesNotContain("server", artifact);
        Assert.DoesNotContain("private-share", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("path=[redacted]", document.RootElement.GetProperty("network").GetProperty("share").GetString());
    }

    [Fact]
    public async Task RedactsUnixAndFilePathsRecursively()
    {
        var artifact = await WriteRawJsonAsync("""{"items":["/home/operator/private.DATA","file:///var/lib/grdp/private.DATA"]}""");

        Assert.DoesNotContain("operator", artifact);
        Assert.DoesNotContain("var/lib", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.All(document.RootElement.GetProperty("items").EnumerateArray(), item => Assert.Equal("[redacted]", item.GetString()));
    }

    [Fact]
    public async Task RedactsPipeUrisRegardlessOfHost()
    {
        var artifact = await WriteRawJsonAsync("""{"diagnostic":"net.pipe://localhost/pipe/private-id","nested":{"uri":"net.pipe://machine/pipe/secret-id"}}""");

        Assert.DoesNotContain("private-id", artifact);
        Assert.DoesNotContain("secret-id", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("[redacted]", document.RootElement.GetProperty("diagnostic").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("uri").GetString());
    }

    [Fact]
    public async Task RedactsCompoundSensitiveKeysRecursivelyWhilePreservingBenignValues()
    {
        var artifact = await WriteRawJsonAsync("""
            {"licenseServer":"private-license-server","licenseHost":"private-license-host","nested":{"tokenValue":"abc123","passwordHash":"hash-value","APIKey":"api-key-value","apiKeyValue":"api-key-value-2","credential_id":"credential-value","secret-code":"secret-value","authorizationHeader":"authorization-value","COOKIE_header":"cookie-value"},"status":"License server is configured","tokenizer":"not-sensitive","authoritativeStatus":"configured","cookbookTitle":"guide","retryCount":2}
            """);

        Assert.DoesNotContain("abc123", artifact);
        Assert.DoesNotContain("private-license-server", artifact);
        Assert.DoesNotContain("private-license-host", artifact);
        Assert.DoesNotContain("hash-value", artifact);
        Assert.DoesNotContain("api-key-value", artifact);
        Assert.DoesNotContain("api-key-value-2", artifact);
        Assert.DoesNotContain("credential-value", artifact);
        Assert.DoesNotContain("secret-value", artifact);
        Assert.DoesNotContain("authorization-value", artifact);
        Assert.DoesNotContain("cookie-value", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("[redacted]", document.RootElement.GetProperty("licenseServer").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("licenseHost").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("tokenValue").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("passwordHash").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("APIKey").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("apiKeyValue").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("credential_id").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("secret-code").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("authorizationHeader").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("nested").GetProperty("COOKIE_header").GetString());
        Assert.Equal("License server is configured", document.RootElement.GetProperty("status").GetString());
        Assert.Equal("not-sensitive", document.RootElement.GetProperty("tokenizer").GetString());
        Assert.Equal("configured", document.RootElement.GetProperty("authoritativeStatus").GetString());
        Assert.Equal("guide", document.RootElement.GetProperty("cookbookTitle").GetString());
        Assert.Equal(2, document.RootElement.GetProperty("retryCount").GetInt32());
    }

    [Fact]
    public async Task RedactsEmbeddedCredentialsRecursivelyWhilePreservingNormalMessages()
    {
        var artifact = await WriteRawJsonAsync("""
            {"message":"Request used Authorization: Bearer authorization-secret-901","nested":{"messages":["Proxy sent Bearer bearer-secret-902","API token=api-token-secret-903","Set-Cookie: session=cookie-secret-904; Path=/; HttpOnly","Cookie: session=cookie-secret-905; theme=dark"]},"status":"Bearer authentication is required","detail":"Cookie response header was unavailable","note":"API token validation failed"}
            """);

        Assert.DoesNotContain("authorization-secret-901", artifact);
        Assert.DoesNotContain("bearer-secret-902", artifact);
        Assert.DoesNotContain("api-token-secret-903", artifact);
        Assert.DoesNotContain("cookie-secret-904", artifact);
        Assert.DoesNotContain("cookie-secret-905", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("Request used Authorization: [redacted]", document.RootElement.GetProperty("message").GetString());
        Assert.Equal("Proxy sent Bearer [redacted]", document.RootElement.GetProperty("nested").GetProperty("messages")[0].GetString());
        Assert.Equal("API token=[redacted]", document.RootElement.GetProperty("nested").GetProperty("messages")[1].GetString());
        Assert.Equal("Set-Cookie: [redacted]", document.RootElement.GetProperty("nested").GetProperty("messages")[2].GetString());
        Assert.Equal("Cookie: [redacted]", document.RootElement.GetProperty("nested").GetProperty("messages")[3].GetString());
        Assert.Equal("Bearer authentication is required", document.RootElement.GetProperty("status").GetString());
        Assert.Equal("Cookie response header was unavailable", document.RootElement.GetProperty("detail").GetString());
        Assert.Equal("API token validation failed", document.RootElement.GetProperty("note").GetString());
    }

    [Fact]
    public async Task RedactsEmbeddedCredentialsInJsonPropertyNames()
    {
        var artifact = await WriteRawJsonAsync("""{"Authorization: Bearer property-name-secret-906":"safe-value"}""");

        Assert.DoesNotContain("property-name-secret-906", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("[redacted]", document.RootElement.GetProperty("Authorization: [redacted]").GetString());
    }

    [Fact]
    public async Task RedactsTokenAssignmentsAndQuotedCookieHeadersInStringsAndPropertyNames()
    {
        var artifact = await WriteRawJsonAsync("""
            {"message":"token=generic-token-secret-911 access_token: \"access-token-secret-912\" refresh_token='refresh-token-secret-913'","headers":["Cookie: \"session=cookie-secret-914; theme=dark\"","Set-Cookie: session=\"cookie-secret-915\"; Path=/; HttpOnly"],"access_token=property-name-secret-916":"safe-value","status":"Token validation is required","detail":"Cookie response header was unavailable"}
            """);

        Assert.DoesNotContain("generic-token-secret-911", artifact);
        Assert.DoesNotContain("access-token-secret-912", artifact);
        Assert.DoesNotContain("refresh-token-secret-913", artifact);
        Assert.DoesNotContain("cookie-secret-914", artifact);
        Assert.DoesNotContain("cookie-secret-915", artifact);
        Assert.DoesNotContain("property-name-secret-916", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("token=[redacted] access_token: [redacted] refresh_token=[redacted]", document.RootElement.GetProperty("message").GetString());
        Assert.Equal("Cookie: [redacted]", document.RootElement.GetProperty("headers")[0].GetString());
        Assert.Equal("Set-Cookie: [redacted]", document.RootElement.GetProperty("headers")[1].GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("access_token=[redacted]").GetString());
        Assert.Equal("Token validation is required", document.RootElement.GetProperty("status").GetString());
        Assert.Equal("Cookie response header was unavailable", document.RootElement.GetProperty("detail").GetString());
    }

    [Fact]
    public async Task RedactsEntireMultiCookieHeaderPayloadUntilNewlineInStringsAndPropertyNames()
    {
        var artifact = await WriteRawJsonAsync("""
            {"message":"Proxy Cookie: session=public; csrf=\"secret-value\"\nUnrelated text after newline","Set-Cookie: session=public; csrf=\"property-secret-value\"\nUnrelated property text":"safe-value"}
            """);

        Assert.DoesNotContain("secret-value", artifact);
        Assert.DoesNotContain("property-secret-value", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("Proxy Cookie: [redacted]\nUnrelated text after newline", document.RootElement.GetProperty("message").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("Set-Cookie: [redacted]\nUnrelated property text").GetString());
    }

    [Fact]
    public async Task RedactsTokenAssignmentsAndQuotedCookieHeadersInLogs()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        Directory.CreateDirectory(output);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        await store.WriteLogAsync(output,
            ["refresh_token=log-refresh-secret-917 Cookie: 'session=log-cookie-secret-918'", "Token validation failed"],
            TestContext.Current.CancellationToken);

        var log = await File.ReadAllTextAsync(Path.Combine(output, "run.log"), TestContext.Current.CancellationToken);
        Assert.DoesNotContain("log-refresh-secret-917", log);
        Assert.DoesNotContain("log-cookie-secret-918", log);
        Assert.Contains("refresh_token=[redacted] Cookie: [redacted]", log);
        Assert.Contains("Token validation failed", log);
    }

    [Fact]
    public async Task RedactsConnectionStringsPrivateKeysAndPasswordAssignments()
    {
        var artifact = await WriteRawJsonAsync("""
            {"connectionString":"Server=db;Password=connection-secret-907","privateKey":"private-key-secret-908","message":"Database retry used password=embedded-secret-909","password=property-name-secret-910":"safe-value","status":"Password policy is documented"}
            """);

        Assert.DoesNotContain("connection-secret-907", artifact);
        Assert.DoesNotContain("private-key-secret-908", artifact);
        Assert.DoesNotContain("embedded-secret-909", artifact);
        Assert.DoesNotContain("property-name-secret-910", artifact);
        using var document = JsonDocument.Parse(artifact);
        Assert.Equal("[redacted]", document.RootElement.GetProperty("connectionString").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("privateKey").GetString());
        Assert.Equal("Database retry used password=[redacted]", document.RootElement.GetProperty("message").GetString());
        Assert.Equal("[redacted]", document.RootElement.GetProperty("password=[redacted]").GetString());
        Assert.Equal("Password policy is documented", document.RootElement.GetProperty("status").GetString());
    }

    [Fact]
    public async Task EclipseOutputsArePublishedAsMetadataNotRawArtifacts()
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        var work = Path.Combine(root, "jobs", "1", "work");
        Directory.CreateDirectory(output);
        Directory.CreateDirectory(work);
        var source = Path.Combine(work, "CASE.PRT");
        await File.WriteAllTextAsync(source, "C:\\licenses\\secret LICENSE server=private-host", TestContext.Current.CancellationToken);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var metadata = await store.DescribeEclipseOutputsAsync([source], TestContext.Current.CancellationToken);
        await store.WriteJsonAsync(output, "result.json", new { outputFiles = metadata }, TestContext.Current.CancellationToken);

        var artifact = await File.ReadAllTextAsync(Path.Combine(output, "result.json"), TestContext.Current.CancellationToken);
        Assert.DoesNotContain("secret", artifact);
        Assert.DoesNotContain("private-host", artifact);
        var outputFile = Assert.Single(metadata);
        Assert.Equal("CASE.PRT", outputFile.Filename);
        Assert.Equal(46, outputFile.SizeBytes);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("C:\\licenses\\secret LICENSE server=private-host"))), outputFile.Sha256);
    }

    [Fact]
    public async Task EclipseOutputMetadataSerializesToBackendValidatorContract()
    {
        var work = Path.Combine(root, "jobs", "2", "work");
        Directory.CreateDirectory(work);
        var source = Path.Combine(work, "CASE.ECLEND");
        await File.WriteAllTextAsync(source, "ok", TestContext.Current.CancellationToken);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));

        var metadata = await store.DescribeEclipseOutputsAsync([source], TestContext.Current.CancellationToken);
        var result = JsonSerializer.SerializeToElement(new
        {
            outputFiles = metadata.Select(file => new { name = file.Filename, sizeBytes = file.SizeBytes, sha256 = file.Sha256 })
        });
        var output = Assert.Single(result.GetProperty("outputFiles").EnumerateArray());

        Assert.Equal(["name", "sha256", "sizeBytes"], output.EnumerateObject().Select(property => property.Name).OrderBy(name => name));
        Assert.False(output.TryGetProperty("filename", out _));
        Assert.Equal("CASE.ECLEND", output.GetProperty("name").GetString());
        Assert.True(output.GetProperty("sizeBytes").GetInt64() >= 0);
        Assert.Matches("^[0-9a-f]{64}$", output.GetProperty("sha256").GetString());
    }

    public void Dispose()
    {
        if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
    }

    private async Task<string> WriteRawJsonAsync(string rawJson)
    {
        var output = Path.Combine(root, "jobs", "1", "output");
        Directory.CreateDirectory(output);
        var store = new ArtifactStore(new StorageResolver(Options.Create(new WorkerOptions { StorageRoot = root })));
        using var document = JsonDocument.Parse(rawJson);
        await store.WriteJsonElementAsync(output, "result.json", document.RootElement, TestContext.Current.CancellationToken);
        return await File.ReadAllTextAsync(Path.Combine(output, "result.json"), TestContext.Current.CancellationToken);
    }
}
