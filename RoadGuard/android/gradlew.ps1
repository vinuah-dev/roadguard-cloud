# PowerShell wrapper for gradlew.bat
$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$batFile = Join-Path $ScriptDir "gradlew.bat"
& cmd.exe /c "`"$batFile`" $($args -join ' ')"
exit $LASTEXITCODE
