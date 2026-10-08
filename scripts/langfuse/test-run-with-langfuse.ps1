$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot 'run-with-langfuse.ps1'
if (-not (Test-Path -LiteralPath $launcher)) { throw 'Missing Langfuse environment loader' }
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('langfuse-loader-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $scratch | Out-Null
$keys = @('LANGFUSE_ENABLED','LANGFUSE_BASE_URL','LANGFUSE_PUBLIC_KEY','LANGFUSE_SECRET_KEY','LANGFUSE_ENVIRONMENT','LANGFUSE_CAPTURE_CONTENT','LANGFUSE_SAMPLE_RATE')
$before = @{}
foreach ($key in $keys) { $before[$key] = [Environment]::GetEnvironmentVariable($key, 'Process'); [Environment]::SetEnvironmentVariable($key, $null, 'Process') }
try {
    $probe = Join-Path $scratch 'probe.ps1'
    @'
[pscustomobject]@{
    Enabled = $env:LANGFUSE_ENABLED
    Base = $env:LANGFUSE_BASE_URL
    Public = $env:LANGFUSE_PUBLIC_KEY
    Secret = $env:LANGFUSE_SECRET_KEY
    Environment = $env:LANGFUSE_ENVIRONMENT
    Capture = $env:LANGFUSE_CAPTURE_CONTENT
    Sample = $env:LANGFUSE_SAMPLE_RATE
} | ConvertTo-Json -Compress
'@ | Set-Content -LiteralPath $probe -Encoding UTF8
    $config = Join-Path $scratch '.env'
    @'
# fake credentials only
LANGFUSE_ENABLED=true
LANGFUSE_BASE_URL="https://example.invalid"
LANGFUSE_PUBLIC_KEY='fake-public'
LANGFUSE_SECRET_KEY="$(throw 'must never execute')"
LANGFUSE_ENVIRONMENT=from-file
LANGFUSE_CAPTURE_CONTENT=false
LANGFUSE_SAMPLE_RATE=0.25
IGNORED=$(throw 'must never execute')
'@ | Set-Content -LiteralPath $config -Encoding UTF8
    $env:LANGFUSE_ENVIRONMENT = 'from-process'
    $actual = (. $launcher -ConfigFile $config -Command powershell -CommandArguments @('-NoProfile','-ExecutionPolicy','Bypass','-File',$probe)) | ConvertFrom-Json
    if ($actual.Enabled -ne 'true' -or $actual.Base -ne 'https://example.invalid' -or $actual.Public -ne 'fake-public' -or $actual.Capture -ne 'false') { throw 'Quoted/BOM config was not loaded' }
    if ($actual.Environment -ne 'from-process') { throw 'Existing process environment lost precedence' }
    if ($actual.Secret -ne "$( '$' )(throw 'must never execute')") { throw 'Config text was evaluated' }
    if ($actual.Sample) { throw 'Non-allowlisted key was loaded' }
    if ($env:LANGFUSE_ENABLED -or $env:LANGFUSE_SECRET_KEY -or $env:LANGFUSE_ENVIRONMENT -ne 'from-process') { throw 'Launcher did not restore environment' }
    'Langfuse loader tests passed'
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $before[$key], 'Process') }
    Remove-Item -LiteralPath $probe,$config -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $scratch -Force
}
