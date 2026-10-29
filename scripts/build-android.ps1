param([switch]$Offline, [string]$GradleExecutable)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$env:JAVA_HOME -and $env:ProgramFiles) {
    $studioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath $studioJava) { $env:JAVA_HOME = $studioJava }
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tooling\gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tooling\android-user'
if (!$GradleExecutable) { $GradleExecutable = Join-Path $projectRoot 'gradlew.bat' }
$buildArguments = @('--no-daemon', '--console=plain', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
if ($Offline) { $buildArguments = @('--offline') + $buildArguments }
Push-Location $projectRoot
try { & $GradleExecutable @buildArguments; if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." } }
finally { Pop-Location }
