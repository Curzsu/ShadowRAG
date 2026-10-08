param(
    [string] $ConfigFile = (Join-Path $PSScriptRoot '../../.env.langfuse.local'),
    [string] $Command = 'mvn',
    [string[]] $CommandArguments = @('spring-boot:run')
)
$ErrorActionPreference = 'Stop'
$allowed = @('LANGFUSE_ENABLED','LANGFUSE_BASE_URL','LANGFUSE_PUBLIC_KEY','LANGFUSE_SECRET_KEY','LANGFUSE_ENVIRONMENT','LANGFUSE_CAPTURE_CONTENT')
$changed = @{}
$childExitCode = 0
try {
    if (Test-Path -LiteralPath $ConfigFile) {
        foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $ConfigFile).Path)) {
            $entry = $line.Trim().TrimStart([char]0xFEFF)
            if (-not $entry -or $entry.StartsWith('#')) { continue }
            $parts = $entry -split '=', 2
            if ($parts.Count -ne 2) { continue }
            $key = $parts[0].Trim()
            if ($key -notin $allowed) { continue }
            if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) { continue }
            $value = $parts[1].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $changed[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
    $global:LASTEXITCODE = 0
    & $Command @CommandArguments
    $childExitCode = $LASTEXITCODE
} finally {
    foreach ($key in $changed.Keys) { [Environment]::SetEnvironmentVariable($key, $changed[$key], 'Process') }
}
if ($MyInvocation.InvocationName -ne '.') { exit $childExitCode }
if ($childExitCode -ne 0) { throw "Child process exited with code $childExitCode" }
