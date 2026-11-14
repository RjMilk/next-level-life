param(
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$GradleCache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)

# Offline JVM verification of the real pose/catalog/routine logic. This does not compile Android/Compose.
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $workspace
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "JDK not found: $java" }

function Find-CachedJar([string]$Module, [string]$Version = '') {
    $moduleRoot = Join-Path $GradleCache $Module
    if ($Version) { $moduleRoot = Join-Path $moduleRoot $Version }
    $jar = Get-ChildItem -LiteralPath $moduleRoot -Recurse -Filter '*.jar' | Sort-Object FullName -Descending | Select-Object -First 1
    if (!$jar) { throw "Cached dependency missing: $Module $Version" }
    return $jar.FullName
}

$compiler = Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable'
$version = (Get-Item -LiteralPath $compiler).Directory.Parent.Name
$stdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib' $version
$reflect = Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect' $version
$scriptRuntime = Find-CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime' $version
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm'
$annotations = Find-CachedJar 'org.jetbrains/annotations'
$junit = Find-CachedJar 'junit/junit'
$hamcrest = Find-CachedJar 'org.hamcrest/hamcrest-core'
$compilerClasspath = ($compiler, $stdlib, $reflect, $scriptRuntime, $coroutines, $annotations) -join ';'
$testClasspath = ($stdlib, $annotations, $junit, $hamcrest) -join ';'
$output = Join-Path $workspace 'app/build/gym-jvm'
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Diary and Workouts also contain Android persistence. Extract their exact pure declarations,
# rather than replacing tested logic with stubs. Fail if source boundaries change.
$diary = Get-Content app/src/main/java/com/nextlevellife/app/data/Diary.kt -Encoding UTF8 -Raw
$measurement = [regex]::Match($diary, '(?ms)^enum class Measurement \{.*?^\}')
$newSet = [regex]::Match($diary, '(?ms)^data class NewSet\(.*?^\)')
$migrations = [regex]::Match($diary, '(?ms)^    internal val MIGRATIONS: List<List<String>> = listOf\(.*?^    \)')
$schemaVersion = [regex]::Match($diary, '(?m)^    internal val SCHEMA_VERSION:.*$')
$sqlUuid = [regex]::Match($diary, '(?m)^private const val SQL_UUID =\r?\n.*\r?\n.*')
$workouts = Get-Content app/src/main/java/com/nextlevellife/app/data/Workouts.kt -Encoding UTF8 -Raw
$workoutMath = [regex]::Match($workouts, '(?ms)^internal object WorkoutMath \{.*\z')
if (!$measurement.Success -or !$newSet.Success -or !$workoutMath.Success -or !$migrations.Success -or !$schemaVersion.Success -or !$sqlUuid.Success) { throw 'Pure declaration extraction failed' }
$pureData = Join-Path $output 'PureData.kt'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($pureData, "package com.nextlevellife.app.data`n" + $sqlUuid.Value + "`n" + $measurement.Value + "`n" + $newSet.Value + "`nobject Diary {`n" + $schemaVersion.Value + "`n" + $migrations.Value + "`n}`n" + $workoutMath.Value, $utf8)

# ExpandableText is pure Kotlin; extract its small production text/level dependencies from Android-bearing files.
function Extract-Required([string]$Text, [string]$Pattern) {
    $match = [regex]::Match($Text, $Pattern)
    if (!$match.Success) { throw "Pure declaration extraction failed: $Pattern" }
    return $match.Value
}
$statsMath = Get-Content app/src/main/java/com/nextlevellife/app/stats/StatsMath.kt -Encoding UTF8 -Raw
$statsText = Get-Content app/src/main/java/com/nextlevellife/app/stats/StatsText.kt -Encoding UTF8 -Raw
$stat = Extract-Required $statsMath '(?ms)^enum class Stat\(.*?^\}'
$level = Extract-Required $statsMath '(?m)^    fun levelFor\(.*$'
$statLine = Extract-Required $statsText '(?m)^    fun statLevel\(.*$'
$nbsp = Extract-Required $statsText '(?m)^    private const val NBSP =.*$'
$pureStats = Join-Path $output 'PureStats.kt'
[IO.File]::WriteAllText($pureStats, "package com.nextlevellife.app.stats`nimport kotlin.math.floor`nimport kotlin.math.sqrt`n$stat`nobject StatsMath {`n$level`n}`nobject StatsText {`n$nbsp`n$statLine`n}", $utf8)
$complexScreen = Get-Content app/src/main/java/com/nextlevellife/app/ui/ComplexScreen.kt -Encoding UTF8 -Raw
$plural = Extract-Required $complexScreen '(?ms)^internal fun pluralRu\(.*?^\}'
$pureUi = Join-Path $output 'PureUi.kt'
[IO.File]::WriteAllText($pureUi, "package com.nextlevellife.app.ui`n$plural`n", $utf8)

$sources = @(Get-ChildItem app/src/main/java/com/nextlevellife/app/pose -Filter '*.kt' | Where-Object Name -notin @('PoseCamera.kt', 'PoseLog.kt', 'RepSound.kt') | Select-Object -ExpandProperty FullName)
$sources += @('app/src/main/java/com/nextlevellife/app/data/Exercises.kt', 'app/src/main/java/com/nextlevellife/app/data/Gym.kt', 'app/src/main/java/com/nextlevellife/app/data/Routines.kt', 'app/src/main/java/com/nextlevellife/app/data/RoutineMath.kt', $pureData)
$sources += @($pureStats, $pureUi, 'app/src/main/java/com/nextlevellife/app/ui/ExpandableText.kt', 'app/src/test/java/com/nextlevellife/app/ui/ExpandableTextTest.kt')
# Log tests and HoldCounterTest's replay case need Android logging. Preserve its shared pose fixtures.
$holdTest = Get-Content app/src/test/java/com/nextlevellife/app/pose/HoldCounterTest.kt -Encoding UTF8 -Raw
$holdFixtures = $holdTest.Substring(0, $holdTest.IndexOf('class HoldCounterTest'))
$fixtureFile = Join-Path $output 'PlankFixtures.kt'
[IO.File]::WriteAllText($fixtureFile, $holdFixtures, $utf8)
$sources += $fixtureFile
$poseTests = @(Get-ChildItem app/src/test/java/com/nextlevellife/app/pose -Filter '*.kt' | Where-Object Name -notin @('PoseLogFormatTest.kt', 'PoseLogReplayTest.kt', 'HoldCounterTest.kt'))
$dataTests = @(Get-ChildItem app/src/test/java/com/nextlevellife/app/data -Filter '*Test.kt' | Where-Object { $_.Name -like 'Gym*Test.kt' -or $_.Name -eq 'RoutinesTest.kt' })
$sources += $poseTests.FullName
$sources += $dataTests.FullName

Write-Host "Compiling gym JVM verification with Kotlin $version..."
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $testClasspath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed ($LASTEXITCODE)" }
$tests = @($poseTests | Where-Object Name -like '*Test.kt' | ForEach-Object { 'com.nextlevellife.app.pose.' + $_.BaseName })
$tests += @($dataTests | ForEach-Object { 'com.nextlevellife.app.data.' + $_.BaseName })
$tests += 'com.nextlevellife.app.ui.ExpandableTextTest'
& $java '-Dfile.encoding=UTF-8' -cp "$classes;$testClasspath" org.junit.runner.JUnitCore @tests
if ($LASTEXITCODE -ne 0) { throw "Gym tests failed ($LASTEXITCODE)" }
