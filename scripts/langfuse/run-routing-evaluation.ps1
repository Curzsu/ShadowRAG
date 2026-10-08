param(
    [string] $Dataset,
    [string] $RunId = ('routing-eval-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid run id' }
$project=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runDir=Join-Path $project "docs/eval/agent_routing/results/$RunId"
$keys=@('LANGFUSE_ROUTING_EVAL','ROUTING_EVAL_DATASET','ROUTING_EVAL_OUTPUT_DIR','ROUTING_EVAL_RUN_ID')
$saved=@{}
foreach($key in $keys) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
Push-Location $project
try {
    $arguments=@('scripts/langfuse/routing_evaluation.py','prepare',$runDir)
    if($Dataset) { $arguments+=@('--dataset',$Dataset) }
    & python @arguments
    if($LASTEXITCODE -ne 0) { throw 'Dataset preparation failed' }
    $env:LANGFUSE_ROUTING_EVAL='true'
    $env:ROUTING_EVAL_DATASET=Join-Path $runDir 'dataset.jsonl'
    $env:ROUTING_EVAL_OUTPUT_DIR=$runDir
    $env:ROUTING_EVAL_RUN_ID=$RunId
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command mvn -CommandArguments @('-Dtest=RoutingEvaluationExportTest*','test')
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command python -CommandArguments @('scripts/langfuse/routing_evaluation.py','score',$runDir,'--upload')
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command python -CommandArguments @('scripts/langfuse/routing_evaluation.py','verify',$runDir)
    Write-Output "Routing artifacts: $runDir"
} finally {
    foreach($key in $keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    Pop-Location
}
