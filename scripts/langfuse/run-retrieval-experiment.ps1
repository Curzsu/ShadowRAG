param(
    [string] $DatasetName = 'retrieval-eval-50',
    [int] $Limit = 10,
    [Parameter(Mandatory=$true)] [string] $Username,
    [string] $RunId = ('retrieval-exp-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
)
$ErrorActionPreference = 'Stop'
if ($Limit -lt 1 -or $RunId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid limit or run id' }
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runDir = Join-Path $project "docs/eval/retrieval/results/$RunId"
$envKeys = @('LANGFUSE_RETRIEVAL_EVAL','RETRIEVAL_EVAL_DATASET','RETRIEVAL_EVAL_OUTPUT_DIR','RETRIEVAL_EVAL_USER','RETRIEVAL_EVAL_RUN_ID')
$saved = @{}
foreach ($key in $envKeys) { $saved[$key] = [Environment]::GetEnvironmentVariable($key,'Process') }
Push-Location $project
try {
    if (Test-Path -LiteralPath $runDir) { throw 'Run already exists; use a new RunId' }
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command python -CommandArguments @('scripts/langfuse/retrieval_experiments.py','--dataset-name',$DatasetName,'--limit',"$Limit",'--output-dir',$runDir)
    $env:LANGFUSE_RETRIEVAL_EVAL = 'true'
    $env:RETRIEVAL_EVAL_DATASET = Join-Path $runDir 'dataset.json'
    $env:RETRIEVAL_EVAL_OUTPUT_DIR = $runDir
    $env:RETRIEVAL_EVAL_USER = $Username
    $env:RETRIEVAL_EVAL_RUN_ID = $RunId
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command mvn -CommandArguments @('-Dtest=RetrievalEvaluationExportTest*','test')
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command python -CommandArguments @('docs/interview/eval_retrieval.py','--java-results',(Join-Path $runDir 'java-results.jsonl'),'--dataset',(Join-Path $runDir 'dataset.json'),'--output-dir',$runDir,'--upload-scores')
    . "$PSScriptRoot/run-with-langfuse.ps1" -Command python -CommandArguments @('scripts/langfuse/verify_retrieval_experiment.py',$runDir)
    Write-Output "Experiment artifacts: $runDir"
} finally {
    foreach ($key in $envKeys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    Pop-Location
}
