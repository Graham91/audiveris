param(
    [string]$ApiKey = "",
    [string]$Endpoint = "https://api.openai.com/v1/responses",
    [string]$Model = "gpt-4.1-mini",
    [string]$WindowId = "smoke-window-0001",
    [int]$TargetPartCount = 4,
    [string]$OutputDir = ".",
    [string[]]$NoteKeys = @(
        "p=P1|m=1|i=0|n=1|v=1|s=1|d=2|pc=C04|c=0",
        "p=P1|m=1|i=0|n=2|v=1|s=1|d=2|pc=E04|c=1",
        "p=P2|m=1|i=0|n=1|v=1|s=1|d=2|pc=C03|c=0"
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Resolve API key from parameter or common environment names.
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $ApiKey = $env:AUDIVERIS_PART_RESOLUTION_KEY
}
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $ApiKey = $env:OPENAI_API_KEY
}
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $ApiKey = $env:API_KEY
}

# Final fallback: prompt interactively so shell/env propagation is not required.
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $secure = Read-Host "Enter API key" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $ApiKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "Missing API key. Set AUDIVERIS_PART_RESOLUTION_KEY/OPENAI_API_KEY, pass -ApiKey, or enter at prompt."
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

$instruction = @"
Return ONLY JSON with this schema:
{
  "schemaVersion": "1.0.0",
  "windowId": "$WindowId",
  "targetPartCount": $TargetPartCount,
  "assignments": [
    {"noteKey": "...", "partIndex": 1, "confidence": 0.0}
  ]
}
Rules:
- Use only provided note keys.
- partIndex must be in range 1..$TargetPartCount.
- confidence must be in range 0..1.
Provided note keys:
$($NoteKeys -join "`n")
"@

$payload = @{
    model = $Model
    input = @(
        @{
            role = "user"
            content = @(
                @{
                    type = "input_text"
                    text = $instruction
                }
            )
        }
    )
    text = @{
        format = @{
            type = "json_object"
        }
    }
} | ConvertTo-Json -Depth 20

$headers = @{
    Authorization = "Bearer $ApiKey"
    "Content-Type" = "application/json"
}

$rawOut = Join-Path $OutputDir "api-smoke-raw-response.json"
$parsedOut = Join-Path $OutputDir "api-smoke-parsed-response.json"

Write-Host "Calling endpoint: $Endpoint"
Write-Host "Model: $Model"

$resp = Invoke-RestMethod -Method Post -Uri $Endpoint -Headers $headers -Body $payload

$resp | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -Path $rawOut

$hasProp = {
    param($obj, [string]$name)
    return ($null -ne $obj) -and ($null -ne $obj.PSObject) -and ($null -ne $obj.PSObject.Properties[$name])
}

$text = $null
if (& $hasProp $resp 'output_text') {
    $candidate = $resp.output_text
    if (-not [string]::IsNullOrWhiteSpace([string]$candidate)) {
        $text = [string]$candidate
    }
}

if (-not $text -and (& $hasProp $resp 'output')) {
    foreach ($item in $resp.output) {
        if (-not (& $hasProp $item 'content')) { continue }
        foreach ($c in $item.content) {
            if ((& $hasProp $c 'text') -and -not [string]::IsNullOrWhiteSpace([string]$c.text)) {
                $text = [string]$c.text
                break
            }

            if ((& $hasProp $c 'type') -and ([string]$c.type -eq 'output_text') -and (& $hasProp $c 'text')) {
                $text = [string]$c.text
                break
            }
        }
        if ($text) { break }
    }
}

if (-not $text) {
    throw "No parsable text content found in API response. See $rawOut"
}

$parsed = $text | ConvertFrom-Json

# Basic validation for framework contract
if (-not (& $hasProp $parsed 'assignments')) {
    throw "Response missing assignments array. See $rawOut"
}

$unknown = @()
$badPart = @()
$badConf = @()
foreach ($a in $parsed.assignments) {
    if ($NoteKeys -notcontains [string]$a.noteKey) {
        $unknown += [string]$a.noteKey
    }

    $part = [int]$a.partIndex
    if ($part -lt 1 -or $part -gt $TargetPartCount) {
        $badPart += "$($a.noteKey):$part"
    }

    $conf = [double]$a.confidence
    if ($conf -lt 0.0 -or $conf -gt 1.0) {
        $badConf += "$($a.noteKey):$conf"
    }
}

$parsed | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path $parsedOut

Write-Host "Saved raw response: $rawOut"
Write-Host "Saved parsed response: $parsedOut"
Write-Host "Assignments returned: $($parsed.assignments.Count)"

if ($unknown.Count -gt 0) {
    Write-Warning "Unknown note keys: $($unknown -join ', ')"
}
if ($badPart.Count -gt 0) {
    Write-Warning "Out-of-range partIndex: $($badPart -join ', ')"
}
if ($badConf.Count -gt 0) {
    Write-Warning "Out-of-range confidence: $($badConf -join ', ')"
}

if (($unknown.Count -eq 0) -and ($badPart.Count -eq 0) -and ($badConf.Count -eq 0)) {
    Write-Host "Smoke test PASSED: response matches expected contract checks."
} else {
    Write-Host "Smoke test completed with contract warnings."
}
