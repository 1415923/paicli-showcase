$ErrorActionPreference = 'Stop'
$currentLocation = Get-Location
if ($null -eq $currentLocation.Provider -or
        $currentLocation.Provider.Name -ne 'FileSystem') {
    throw 'PaiCLI must be launched from a filesystem directory.'
}
$launchWorkingDirectory = [System.IO.Path]::GetFullPath(
    [string]$currentLocation.ProviderPath)
if (-not [System.IO.Directory]::Exists($launchWorkingDirectory)) {
    throw 'PaiCLI launch working directory does not exist.'
}
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path (Split-Path -Parent $scriptDir) 'lib/paicli-1.2.0.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    [Console]::Error.WriteLine('PaiCLI launcher error: distribution JAR not found.')
    exit 2
}
$java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $java) {
    [Console]::Error.WriteLine('PaiCLI launcher error: Java 21 is not available on PATH.')
    exit 2
}
$javaArguments = @('-jar', $jar) + @($args)

& $java.Source @javaArguments
$exitCode = $LASTEXITCODE

exit $exitCode
