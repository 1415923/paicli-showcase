[CmdletBinding()]
param(
    [ValidateSet('Offline')]
    [string]$Suite = 'Offline',
    [ValidateSet(
        'readonly-single-tool',
        'approval-reject',
        'approval-cancel',
        'ctrl-c-exit',
        'manual-compact',
        'auto-long-task',
        'write-verify-rewind',
        'structured-verify-fail-closed',
        'session-resume-continue',
        'normal-long-plan-approval',
        'plan-tool-scope-sequential-deviation',
        'plan-tool-scope-multi-tool-batch',
        'plan-evidence-semantic-count',
        'plan-v2-unfinished-fail-closed'
    )]
    [string]$Scenario
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$driver = Join-Path $PSScriptRoot 'paicli_uat.py'
$zip = Join-Path $projectRoot 'target\dist\paicli-1.2.0.zip'

if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') {
    throw 'PaiCLI distribution ConPTY UAT is supported only on Windows.'
}
if (-not (Test-Path -LiteralPath $zip -PathType Leaf)) {
    throw "Distribution ZIP not found: $zip"
}

$python = @(
    (Get-Command python -ErrorAction SilentlyContinue),
    (Get-Command py -ErrorAction SilentlyContinue)
) | Where-Object { $_ } | Select-Object -First 1
if (-not $python) { throw 'Python 3 with pywinpty is required for ConPTY UAT.' }

$arguments = @($driver, '--project-root', $projectRoot, '--suite', $Suite)
if ($Scenario) { $arguments += @('--scenario', $Scenario) }

& $python.Source @arguments
$exitCode = $LASTEXITCODE
Write-Host "PAICLI_UAT_EXIT_CODE=$exitCode"
exit $exitCode
