param(
    [string]$BackendBaseUrl = "http://localhost:8080",
    [string]$UserId = ("long-stream-verify-" + (Get-Date -Format "yyyyMMddHHmmss")),
    [int]$TotalSeconds = 95,
    [int]$ChunkIntervalMs = 1000,
    [int]$ChunkSize = 4096,
    [int]$WindowSeconds = 30,
    [int]$CloseTimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Get-JsonValue {
    param(
        $Object,
        [string]$Name,
        $Default = $null
    )

    if ($null -eq $Object) {
        return $Default
    }

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $Default
    }

    return $property.Value
}

function Get-EventCount {
    param(
        [object[]]$Events,
        [string]$Type
    )

    return @($Events | Where-Object { (Get-JsonValue $_ "type") -eq $Type }).Count
}

function To-Int {
    param(
        $Value,
        [int]$Default = 0
    )

    if ($null -eq $Value -or "$Value" -eq "") {
        return $Default
    }

    return [int]$Value
}

$csharpSource = @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

public sealed class LongStreamWsResult
{
    public LongStreamWsResult()
    {
        CloseCode = -1;
        CloseReason = string.Empty;
        ReceiveError = string.Empty;
        Events = new List<string>();
    }

    public int CloseCode { get; set; }
    public string CloseReason { get; set; }
    public string ReceiveError { get; set; }
    public List<string> Events { get; private set; }
}

public static class LongStreamWsClient
{
    public static LongStreamWsResult Run(string wsUrl, int chunkIterations, int chunkIntervalMs, int chunkSize, int closeTimeoutSeconds)
    {
        var result = new LongStreamWsResult();
        var socket = new ClientWebSocket();

        try
        {
            socket.ConnectAsync(new Uri(wsUrl), CancellationToken.None).GetAwaiter().GetResult();

            var receiveTask = Task.Run(async delegate
            {
                var buffer = new byte[8192];

                try
                {
                    while (true)
                    {
                        using (var stream = new MemoryStream())
                        {
                            WebSocketReceiveResult receiveResult;

                            do
                            {
                                receiveResult = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
                                if (receiveResult.Count > 0)
                                {
                                    stream.Write(buffer, 0, receiveResult.Count);
                                }
                            }
                            while (!receiveResult.EndOfMessage);

                            if (receiveResult.MessageType == WebSocketMessageType.Close)
                            {
                                if (socket.State == WebSocketState.CloseReceived)
                                {
                                    await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, receiveResult.CloseStatusDescription, CancellationToken.None);
                                }

                                result.CloseCode = receiveResult.CloseStatus.HasValue ? (int)receiveResult.CloseStatus.Value : 1000;
                                result.CloseReason = receiveResult.CloseStatusDescription ?? string.Empty;
                                return;
                            }

                            if (receiveResult.MessageType == WebSocketMessageType.Text)
                            {
                                result.Events.Add(Encoding.UTF8.GetString(stream.ToArray()));
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    result.ReceiveError = ex.ToString();
                    throw;
                }
            });

            var chunk = new byte[chunkSize];
            for (var index = 0; index < chunk.Length; index++)
            {
                chunk[index] = 7;
            }

            var startMessage = Encoding.UTF8.GetBytes("{\"type\":\"session_started\"}");
            socket.SendAsync(new ArraySegment<byte>(startMessage), WebSocketMessageType.Text, true, CancellationToken.None).GetAwaiter().GetResult();

            for (var i = 0; i < chunkIterations; i++)
            {
                socket.SendAsync(new ArraySegment<byte>(chunk), WebSocketMessageType.Binary, true, CancellationToken.None).GetAwaiter().GetResult();
                Thread.Sleep(chunkIntervalMs);
            }

            var stopMessage = Encoding.UTF8.GetBytes("{\"type\":\"stop\",\"fileName\":\"long-stream-verify.webm\",\"reason\":\"manual_stop\"}");
            socket.SendAsync(new ArraySegment<byte>(stopMessage), WebSocketMessageType.Text, true, CancellationToken.None).GetAwaiter().GetResult();

            var completed = receiveTask.Wait(TimeSpan.FromSeconds(closeTimeoutSeconds));
            if (!completed)
            {
                throw new TimeoutException("WebSocket did not close before timeout.");
            }

            receiveTask.GetAwaiter().GetResult();
            return result;
        }
        finally
        {
            socket.Dispose();
        }
    }
}
'@

Add-Type -TypeDefinition $csharpSource -Language CSharp

$stateUrl = "$BackendBaseUrl/api/demo/state?userId=$([Uri]::EscapeDataString($UserId))"
$backendProbe = Invoke-RestMethod -Uri $stateUrl -Method Get
Assert-Condition ($null -ne $backendProbe) "Backend is unavailable. Start the backend service first."

$expectedWindows = [int][Math]::Ceiling($TotalSeconds / [double]$WindowSeconds)
$chunkIterations = [int][Math]::Ceiling(($TotalSeconds * 1000) / [double]$ChunkIntervalMs)
$wsBaseUrl = $BackendBaseUrl -replace "^http", "ws"
$wsUrl = "$wsBaseUrl/ws/audio?userId=$([Uri]::EscapeDataString($UserId))"

Write-Host "Long stream verification started"
Write-Host "Backend: $BackendBaseUrl"
Write-Host "UserId: $UserId"
Write-Host "WebSocket: $wsUrl"
Write-Host "TotalSeconds: $TotalSeconds"
Write-Host "ChunkIterations: $chunkIterations"
Write-Host "ExpectedWindows: $expectedWindows"

$wsResult = [LongStreamWsClient]::Run($wsUrl, $chunkIterations, $ChunkIntervalMs, $ChunkSize, $CloseTimeoutSeconds)
Assert-Condition ([string]::IsNullOrWhiteSpace($wsResult.ReceiveError)) "Receive loop failed: $($wsResult.ReceiveError)"
Assert-Condition ($wsResult.CloseCode -eq 1000) "Unexpected WebSocket close status: code=$($wsResult.CloseCode) reason=$($wsResult.CloseReason)"

$eventList = @()
foreach ($eventText in $wsResult.Events) {
    Write-Host "WS_EVENT $eventText"
    $eventList += ($eventText | ConvertFrom-Json)
}
Write-Host "WS_CLOSE $($wsResult.CloseCode) $($wsResult.CloseReason)"

$deadline = (Get-Date).AddSeconds($CloseTimeoutSeconds)
$state = $null
$streamSession = $null
$audioEvents = @()
$modelJobs = @()
$conversationMemories = @()

while ((Get-Date) -lt $deadline) {
    $state = Invoke-RestMethod -Uri $stateUrl -Method Get
    $streamSession = @($state.audioStreamSessions)[0]
    $audioEvents = @($state.audioEvents)
    $modelJobs = @($state.modelJobs)
    $conversationMemories = @($state.conversationMemories)

    $sessionWindowCount = To-Int (Get-JsonValue $streamSession "windowCount")
    $processedWindowCount = To-Int (Get-JsonValue $streamSession "processedWindowCount")
    $failedWindowCount = To-Int (Get-JsonValue $streamSession "failedWindowCount")
    $postProcessingStatus = [string](Get-JsonValue $streamSession "sessionPostProcessingStatus")
    $sessionConversationMemoryId = [string](Get-JsonValue $streamSession "sessionConversationMemoryId")

    if ($null -ne $streamSession -and
        $sessionWindowCount -ge $expectedWindows -and
        $processedWindowCount -ge $expectedWindows -and
        $failedWindowCount -eq 0 -and
        $postProcessingStatus -eq "completed" -and
        -not [string]::IsNullOrWhiteSpace($sessionConversationMemoryId) -and
        $audioEvents.Count -ge $expectedWindows -and
        $modelJobs.Count -ge $expectedWindows) {
        break
    }

    Start-Sleep -Seconds 2
}

Assert-Condition ($null -ne $streamSession) "No audio_stream_session record was found."

$sessionStatus = [string](Get-JsonValue $streamSession "status")
$sessionWindowCount = To-Int (Get-JsonValue $streamSession "windowCount")
$processedWindowCount = To-Int (Get-JsonValue $streamSession "processedWindowCount")
$failedWindowCount = To-Int (Get-JsonValue $streamSession "failedWindowCount")
$closeReason = [string](Get-JsonValue $streamSession "closeReason")
$businessSessionStatus = [string](Get-JsonValue $streamSession "sessionStatus")
$postProcessingStatus = [string](Get-JsonValue $streamSession "sessionPostProcessingStatus")
$sessionConversationMemoryId = [string](Get-JsonValue $streamSession "sessionConversationMemoryId")

Assert-Condition ($sessionStatus -eq "closed") "Stream session is not closed. status=$sessionStatus"
Assert-Condition ($businessSessionStatus -eq "closed") "Business session is not closed. session_status=$businessSessionStatus"
Assert-Condition ($sessionWindowCount -eq $expectedWindows) "Unexpected window count. expected=$expectedWindows actual=$sessionWindowCount"
Assert-Condition ($processedWindowCount -eq $expectedWindows) "Unexpected processed window count. expected=$expectedWindows actual=$processedWindowCount"
Assert-Condition ($failedWindowCount -eq 0) "Failed windows detected. failed_window_count=$failedWindowCount"
Assert-Condition ($closeReason -eq "manual_stop") "Unexpected stream close reason: $closeReason"
Assert-Condition ($postProcessingStatus -eq "completed") "Unexpected session post-processing status: $postProcessingStatus"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($sessionConversationMemoryId)) "No session-level conversation memory was linked."
Assert-Condition ($audioEvents.Count -eq $expectedWindows) "Unexpected audio_event count. expected=$expectedWindows actual=$($audioEvents.Count)"
Assert-Condition ($modelJobs.Count -eq $expectedWindows) "Unexpected model_job count. expected=$expectedWindows actual=$($modelJobs.Count)"

$finalWindows = @($audioEvents | Where-Object { [bool](Get-JsonValue $_ "isFinalWindow") })
$unfinishedEvents = @($audioEvents | Where-Object {
    $status = [string](Get-JsonValue $_ "processingStatus")
    $status -notin @("completed", "discarded")
})
$incompleteJobs = @($modelJobs | Where-Object { [string](Get-JsonValue $_ "status") -ne "completed" })

Assert-Condition ($finalWindows.Count -eq 1) "Unexpected final window count. expected=1 actual=$($finalWindows.Count)"
Assert-Condition ($unfinishedEvents.Count -eq 0) "Unfinished audio_event records detected: $($unfinishedEvents | ConvertTo-Json -Depth 4 -Compress)"
Assert-Condition ($incompleteJobs.Count -eq 0) "Incomplete model_job records detected: $($incompleteJobs | ConvertTo-Json -Depth 4 -Compress)"

$streamOpenedCount = Get-EventCount $eventList "stream_opened"
$sessionStartedCount = Get-EventCount $eventList "session_started"
$transcriptCount = Get-EventCount $eventList "incremental_transcript"
$windowStartedCount = Get-EventCount $eventList "window_processing_started"
$windowCompletedCount = Get-EventCount $eventList "window_processing_completed"
$processingStartedCount = Get-EventCount $eventList "processing_started"
$sessionPostProcessingStartedCount = Get-EventCount $eventList "session_post_processing_started"
$processingCompletedCount = Get-EventCount $eventList "processing_completed"
$errorEventCount = Get-EventCount $eventList "error"

Assert-Condition ($streamOpenedCount -eq 1) "Unexpected stream_opened count: $streamOpenedCount"
Assert-Condition ($sessionStartedCount -eq 1) "Unexpected session_started count: $sessionStartedCount"
Assert-Condition ($transcriptCount -ge 1) "No incremental transcript events were observed."
Assert-Condition ($windowStartedCount -eq $expectedWindows) "Unexpected window_processing_started count. expected=$expectedWindows actual=$windowStartedCount"
Assert-Condition ($windowCompletedCount -eq $expectedWindows) "Unexpected window_processing_completed count. expected=$expectedWindows actual=$windowCompletedCount"
Assert-Condition ($processingStartedCount -le 1) "Unexpected processing_started count: $processingStartedCount"
Assert-Condition ($sessionPostProcessingStartedCount -eq 1) "Unexpected session_post_processing_started count: $sessionPostProcessingStartedCount"
Assert-Condition ($processingCompletedCount -eq 1) "Unexpected processing_completed count: $processingCompletedCount"
Assert-Condition ($errorEventCount -eq 0) "WebSocket error events detected. count=$errorEventCount"

$sessionLevelMemories = @($conversationMemories | Where-Object {
    [string](Get-JsonValue $_ "sourceType") -eq "audio_session" -and
    [string](Get-JsonValue $_ "id") -eq $sessionConversationMemoryId
})
Assert-Condition ($sessionLevelMemories.Count -eq 1) "Session-level conversation memory was not visible in state."

$audioEventStatuses = @($audioEvents | ForEach-Object { [string](Get-JsonValue $_ "processingStatus") })
$modelJobStatuses = @($modelJobs | ForEach-Object { [string](Get-JsonValue $_ "status") })

Write-Host ""
Write-Host "Long stream verification passed"
Write-Host "UserId: $UserId"
Write-Host "StreamSessionId: $(Get-JsonValue $streamSession 'id')"
Write-Host "SessionConversationMemoryId: $sessionConversationMemoryId"
Write-Host "WindowCount: $sessionWindowCount"
Write-Host "ProcessedWindowCount: $processedWindowCount"
Write-Host "FailedWindowCount: $failedWindowCount"
Write-Host "AudioEventStatuses: $($audioEventStatuses -join ', ')"
Write-Host "ModelJobStatuses: $($modelJobStatuses -join ', ')"
Write-Host "WS window_processing_started: $windowStartedCount"
Write-Host "WS window_processing_completed: $windowCompletedCount"
Write-Host "WS incremental_transcript: $transcriptCount"
Write-Host "WS processing_completed: $processingCompletedCount"
