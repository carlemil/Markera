[CmdletBinding()]
param(
    [ValidateRange(3, 20)][int]$WarmRuns = 3,
    [ValidateSet('All', 'Test', 'Assemble', 'Gate', 'Edits')][string]$Scenario = 'All',
    [string]$OutputDirectory = '',
    [switch]$SkipForced
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo ('build/profiling/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$out = [IO.Path]::GetFullPath($OutputDirectory)
# Only ignored build output may hold profiles, machine information and logs.
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build')) + [IO.Path]::DirectorySeparatorChar
if (!$out.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputDirectory must be inside this checkout build/.' }
New-Item -ItemType Directory -Force $out | Out-Null
$results = [Collections.Generic.List[object]]::new()
$gate = @(':composeApp:testDebugUnitTest', ':composeApp:assembleDebug')
function Measure-Gradle([string]$Label, [string[]]$Tasks, [switch]$Forced) {
    $log = Join-Path $out ($Label + '.log')
    $arguments = @($Tasks) + @('--profile', '--console=plain', '--no-scan')
    if ($Forced) { $arguments += '--rerun-tasks' }
    $started = Get-Date
    $timer = [Diagnostics.Stopwatch]::StartNew()
    & (Join-Path $repo 'gradlew.bat') @arguments *> $log
    $code = $LASTEXITCODE
    $timer.Stop()
    $profiles = @(Get-ChildItem (Join-Path $repo 'build/reports/profile') -Filter '*.html' -ErrorAction SilentlyContinue | Where-Object LastWriteTime -ge $started)
    $savedProfiles = @()
    foreach ($profile in $profiles) {
        $destination = Join-Path $out ($Label + '-' + $profile.Name)
        Copy-Item $profile.FullName $destination
        $savedProfiles += [IO.Path]::GetFileName($destination)
    }
    foreach ($assets in @('css', 'js')) {
        $source = Join-Path $repo "build/reports/profile/$assets"
        if (Test-Path $source) { Copy-Item $source $out -Recurse -Force }
    }
    $content = "$(Get-Content $log -Raw)"
    $results.Add([pscustomobject]@{
        label = $Label; seconds = [math]::Round($timer.Elapsed.TotalSeconds, 3); exitCode = $code
        forced = [bool]$Forced; tasks = $Tasks; profiles = $savedProfiles
        upToDate = ([regex]::Matches($content, '(?m)^> Task .+ UP-TO-DATE\s*$')).Count
        fromCache = ([regex]::Matches($content, '(?m)^> Task .+ FROM-CACHE\s*$')).Count
        configurationCacheReused = $content.Contains('Reusing configuration cache.')
    })
    $results | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out 'runs.json') -Encoding utf8
    Write-Host "$Label : $($timer.Elapsed.TotalSeconds.ToString('F3')) seconds; exit $code"
    if ($code -ne 0) { throw "Gradle failed ($code): $log" }
}
function Measure-Edit([string]$Label, [string]$RelativePath, [string]$Before, [string]$After) {
    $path = Join-Path $repo $RelativePath
    $bytes = [IO.File]::ReadAllBytes($path)
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    if (!$text.Contains($Before)) { throw "Edit anchor missing: $RelativePath" }
    try {
        [IO.File]::WriteAllText($path, $text.Replace($Before, $After), [Text.UTF8Encoding]::new($false))
        Measure-Gradle $Label $gate
    } finally {
        [IO.File]::WriteAllBytes($path, $bytes)
        $restored = [Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) -ceq [Convert]::ToBase64String($bytes)
        "$RelativePath restored=$restored" | Add-Content (Join-Path $out 'restoration.txt')
        if (!$restored) { throw "Restoration failed: $RelativePath" }
    }
}
Push-Location $repo
try {
    @("commit=$(git rev-parse HEAD)", "status=$(git status --porcelain)", "started=$(Get-Date -Format o)",
      'State: existing user dependency cache and daemon; forced means rerun tasks, not clean caches.') |
        Set-Content (Join-Path $out 'environment.txt')
    & ./gradlew.bat --version >> (Join-Path $out 'environment.txt')
    Get-Content (Join-Path $repo 'gradle/libs.versions.toml') | Select-String '^(agp|kotlin|composeMultiplatform) = ' | ForEach-Object Line | Add-Content (Join-Path $out 'environment.txt')
    Get-CimInstance Win32_Processor | Select-Object Name, NumberOfLogicalProcessors | Format-List | Out-String | Add-Content (Join-Path $out 'environment.txt')
    Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory | Format-List | Out-String | Add-Content (Join-Path $out 'environment.txt')
    foreach ($name in @('Test', 'Assemble', 'Gate')) {
        if ($Scenario -notin @('All', $name)) { continue }
        $tasks = switch ($name) { Test { @($gate[0]) }; Assemble { @($gate[1]) }; Gate { $gate } }
        if (!$SkipForced) { Measure-Gradle "$name-forced" $tasks -Forced }
        for ($i = 1; $i -le $WarmRuns; $i++) { Measure-Gradle "$name-warm-$i" $tasks }
    }
    if ($Scenario -in @('All', 'Edits')) {
        Measure-Edit 'kotlin-edit' 'composeApp/src/commonMain/kotlin/se/kjellstrand/markera/ui/markera/ManualHit.kt' 'MANUAL_HOLE_FALLBACK_PX = 20f' 'MANUAL_HOLE_FALLBACK_PX = 20.0f'
        Measure-Gradle 'kotlin-restored' $gate
        Measure-Edit 'resource-edit' 'composeApp/src/commonMain/composeResources/values/strings.xml' '<string name="markera_detect">Detect</string>' '<string name="markera_detect">Detect target</string>'
        Measure-Gradle 'final-restored-gate' $gate
    }
} finally {
    $medians = foreach ($name in @('Test', 'Assemble', 'Gate')) {
        $values = @($results | Where-Object { $_.label -like "$name-warm-*" -and $_.exitCode -eq 0 } | ForEach-Object seconds | Sort-Object)
        if ($values.Count) {
            $middle = [int][math]::Floor($values.Count / 2)
            $median = if ($values.Count % 2) { $values[$middle] } else { ($values[$middle - 1] + $values[$middle]) / 2 }
            [pscustomobject]@{ scenario = $name; runs = $values.Count; medianSeconds = $median }
        }
    }
    @($medians) | ConvertTo-Json | Set-Content (Join-Path $out 'medians.json') -Encoding utf8
    Pop-Location
}
