using Alify.Controllers;
using Alify.Core.Infrastructure.Doppler;
using Alify.Core.Models;
using Alify.Core.Services;
using Alify.Core.Features.Analysis.Engine.Gemini;
using Alify.Features.Spotify.Events;
using Alify.Features.Spotify.Services;
using Alify.Services;
using Microsoft.Extensions.Options;
using Serilog;
using SpotifyAPI.Web;
using System.Security.Authentication;
using Alify.Core.Infrastructure.Security;

// CLI Mode Handling for Hujjify.exe
if (args.Length > 0)
{
    var arg = args[0].ToLowerInvariant();
    if (arg is "--soft-glide" or "--skip" or "-s")
    {
        Console.WriteLine("[Hujjify] Executing 350ms Soft Glide Skip...");
        await WindowsAudioController.SoftGlideAsync(async () =>
        {
            try
            {
                using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
                var res = await client.PostAsync("http://127.0.0.1:5001/api/player/skip", null);
                if (res.IsSuccessStatusCode)
                {
                    Console.WriteLine("[Hujjify] Skip dispatched to running Hujjify service.");
                    return;
                }
            }
            catch { }

            WindowsAudioController.SendMediaNextTrack();
            Console.WriteLine("[Hujjify] Skip dispatched via Windows Media Bus.");
        });
        Console.WriteLine("[Hujjify] Soft Glide complete.");
        return;
    }
    if (arg is "--duck" or "-d")
    {
        var vol = WindowsAudioController.DuckSpotifyVolume();
        Console.WriteLine($"[Hujjify] Spotify.exe volume ducked to 0% (previous: {vol:P0}).");
        return;
    }
    if (arg is "--restore" or "-r")
    {
        WindowsAudioController.SetSpotifyMasterVolume(1.0f);
        Console.WriteLine("[Hujjify] Spotify.exe volume restored to 100%.");
        return;
    }
    if (arg is "--help" or "-h")
    {
        Console.WriteLine("Hujjify Desktop CLI Commands:");
        Console.WriteLine("  --soft-glide, -s   Instantly duck Spotify.exe, skip to next track, and smoothly fade in over 200ms");
        Console.WriteLine("  --duck, -d         Instantly duck Spotify.exe volume to 0%");
        Console.WriteLine("  --restore, -r      Restore Spotify.exe volume to 100%");
        Console.WriteLine("  --help, -h         Display this help message");
        return;
    }
}

var builder = WebApplication.CreateBuilder(args);

// Configure Serilog
Log.Logger = new LoggerConfiguration()
    .ReadFrom.Configuration(builder.Configuration)
    .Enrich.FromLogContext()
    .WriteTo.Console()
    .CreateLogger();

builder.Host.UseSerilog();

if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("ASPNETCORE_URLS")) && !args.Any(a => a.StartsWith("--urls", StringComparison.OrdinalIgnoreCase)))
{
    builder.WebHost.UseUrls("http://127.0.0.1:5001");
}

var dopplerToken = builder.Configuration["DOPPLER_TOKEN"];
builder.Configuration.AddDoppler(dopplerToken);

builder.Services.AddRazorPages(options =>
{
    options.RootDirectory = "/Pages";
    options.Conventions.AddPageRoute("/Authentication/callback", "/callback");
    options.Conventions.AddPageRoute("/Dashboard/Dashboard", "/Dashboard");
    options.Conventions.AddPageRoute("/Spotify/SpotifyEvents", "/SpotifyEvents");
});

builder.Services.AddControllers();
builder.Services.AddOutputCache();

builder.Services.AddHttpLogging(options =>
{
    options.LoggingFields = Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.RequestMethod |
                           Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.RequestPath |
                           Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.ResponseStatusCode |
                           Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.Duration |
                           Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.RequestHeaders |
                           Microsoft.AspNetCore.HttpLogging.HttpLoggingFields.ResponseHeaders;

    options.RequestHeaders.Add("User-Agent");
    options.RequestHeaders.Add("Accept");
    options.RequestHeaders.Add("Content-Type");
    options.ResponseHeaders.Add("Cache-Control");
    options.CombineLogs = true;
});

builder.Services.AddResponseCompression(options =>
{
    options.EnableForHttps = true;
});

builder.Services.AddDataProtection();
builder.Services.AddSingleton<ISpotifyTokenStore, ProtectedFileSpotifyTokenStore>();

builder.Services.AddSession(options =>
{
    options.IdleTimeout = TimeSpan.FromDays(30);
    options.Cookie.MaxAge = TimeSpan.FromDays(30);
    options.Cookie.HttpOnly = true;
    options.Cookie.IsEssential = true;
    options.Cookie.SameSite = SameSiteMode.Lax;
});

builder.Services.AddHttpClient();
builder.Services.Configure<ApiKeys>(builder.Configuration.GetSection("ApiKeys"));
builder.Services.Configure<SpotifyOptions>(builder.Configuration.GetSection("Spotify"));
builder.Services.AddHttpContextAccessor();
builder.Services.AddSingleton(SpotifyClientConfig.CreateDefault());
builder.Services.AddScoped<SpotifyController>();
builder.Services.AddMemoryCache();

// Add core services
builder.Services.AddHttpClient<FreeLyricsProviderService>();
builder.Services.AddSingleton<FreeLyricsProviderService>();
builder.Services.AddSingleton<Alify.Core.Services.ArtistLyricService>();

// Register Oct 2026 Multi-Provider LLM Waterfall Engine (Groq Qwen 3.8 / GPT-OSS -> Gemini 3.5 Flash -> OpenRouter)
var geminiApiKey = builder.Configuration["ApiKeys:Gemini:ApiKey"];
builder.Services.AddSingleton<IGeminiModelClient>(sp =>
{
    var httpClient = sp.GetRequiredService<IHttpClientFactory>().CreateClient();
    return HttpGeminiModelClient.CreateResilientWaterfallClient(httpClient, geminiApiKey);
});
builder.Services.AddSingleton(ProviderConsent.Granted("qwen/qwen3.8-27b"));
builder.Services.AddSingleton(new BudgetLedger(5000));
builder.Services.AddSingleton<GeminiStanceAnalysisEngine>();

// Add SSE and Spotify services
builder.Services.AddSingleton<QueueService>();
builder.Services.AddSingleton<SseService>();
builder.Services.AddSingleton<ISseService>(sp => sp.GetRequiredService<SseService>());
builder.Services.AddSingleton<ISpotifySubject>(sp => sp.GetRequiredService<SseService>());
builder.Services.AddSingleton<SpotifyRequestCache>();
builder.Services.AddScoped<SpotifyService>(); // Changed from Singleton to Scoped for HttpContext access

// Register the unified Spotify playback monitoring service
builder.Services.AddSingleton<SpotifyPlaybackMonitorService>();
builder.Services.AddHostedService<SpotifyPlaybackMonitorService>(sp => sp.GetRequiredService<SpotifyPlaybackMonitorService>());
builder.Services.Configure<PlaybackMonitorOptions>(options => {
    options.PostSkipCheckDelayMs = 300;
    options.ActivePlaybackPollingIntervalMs = 4000;
});

var app = builder.Build();

if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error");
    app.UseHsts();
    app.UseResponseCompression();
}

app.Use(async (context, next) =>
{
    var logger = context.RequestServices.GetRequiredService<ILogger<Program>>();
    logger.LogInformation("Incoming request: {Method} {Path}", context.Request.Method, context.Request.Path);
    await next();
    logger.LogInformation("Response status: {StatusCode}", context.Response.StatusCode);
});

app.UseHttpLogging();
app.UseOutputCache();
app.UseStaticFiles();
app.UseRouting();
app.UseSession();
app.UseAuthorization();
app.MapControllers();
app.MapRazorPages();

// Minimal API endpoints for cross-device mobile triggers and local automation
app.MapPost("/api/player/soft-glide", async (IServiceProvider sp) =>
{
    await WindowsAudioController.SoftGlideAsync(async () =>
    {
        try
        {
            using var scope = sp.CreateScope();
            var spotifyService = scope.ServiceProvider.GetRequiredService<SpotifyService>();
            var spotifyClient = await spotifyService.GetSpotifyClientAsync();
            if (spotifyClient != null)
            {
                await spotifyClient.Player.SkipNext();
                return;
            }
        }
        catch { }

        WindowsAudioController.SendMediaNextTrack();
    });
    return Results.Ok(new { message = "350ms Soft Glide Skip executed successfully" });
});

app.MapPost("/api/player/skip", async (IServiceProvider sp) =>
{
    try
    {
        using var scope = sp.CreateScope();
        var spotifyService = scope.ServiceProvider.GetRequiredService<SpotifyService>();
        var spotifyClient = await spotifyService.GetSpotifyClientAsync();
        if (spotifyClient != null)
        {
            await spotifyClient.Player.SkipNext();
            return Results.Ok(new { message = "Skipped via Spotify API" });
        }
    }
    catch { }

    WindowsAudioController.SendMediaNextTrack();
    return Results.Ok(new { message = "Skipped via Windows Media Key" });
});

app.Lifetime.ApplicationStarted.Register(() =>
{
    if (Environment.GetEnvironmentVariable("ALIFY_NO_BROWSER") == null && !app.Environment.IsEnvironment("Testing"))
    {
        try
        {
            var targetUrl = "http://127.0.0.1:5001";
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = targetUrl,
                UseShellExecute = true
            });
        }
        catch { /* Ignore in headless environments */ }
    }
});

app.Lifetime.ApplicationStopping.Register(() =>
{
    var sseService = app.Services.GetService<ISseService>();
    sseService?.Cleanup();
});

app.Run();
