param(
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$GradleCache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)

# Offline fallback: compile the actual pure Kotlin sources and run all guide JUnit tests.
# Android/Compose compilation still needs :app:assembleDebug; this does not replace it.
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $workspace
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "JDK not found: $java" }

function Find-CachedJar([string]$Module, [string]$Version = '') {
    $moduleRoot = Join-Path $GradleCache $Module
    if ($Version) { $moduleRoot = Join-Path $moduleRoot $Version }
    $jar = Get-ChildItem -LiteralPath $moduleRoot -Recurse -Filter '*.jar' | Sort-Object FullName -Descending | Select-Object -First 1
    if (!$jar) { throw "Cached dependency missing: $Module $Version (run Gradle once with network access)" }
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
$classes = Join-Path $workspace 'app/build/guide-jvm/classes'
$frames = Join-Path $workspace 'app/build/guide-frames'
New-Item -ItemType Directory -Force -Path $classes, $frames | Out-Null

$sources = @(Get-ChildItem app/src/main/java/com/nextlevellife/app/guide -Filter '*.kt' | Where-Object Name -ne 'GuidePrefs.kt' | Select-Object -ExpandProperty FullName)
# Routines' real PoseSide type depends on the pure pose engine. No Android stubs are used.
$sources += @(Get-ChildItem app/src/main/java/com/nextlevellife/app/pose -Filter '*.kt' | Where-Object Name -notin @('PoseCamera.kt', 'PoseLog.kt', 'RepSound.kt') | Select-Object -ExpandProperty FullName)
$sources += @('app/src/main/java/com/nextlevellife/app/data/Exercises.kt', 'app/src/main/java/com/nextlevellife/app/data/Routines.kt')
$testSources = @(Get-ChildItem app/src/test/java/com/nextlevellife/app/guide -Filter '*.kt')
$sources += $testSources.FullName

Write-Host "Compiling guide JVM sources with Kotlin $version..."
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $testClasspath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed ($LASTEXITCODE)" }
$tests = @($testSources | Where-Object Name -like '*Test.kt' | ForEach-Object { 'com.nextlevellife.app.guide.' + $_.BaseName })
& $java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' "-Dguide.frames.dir=$frames" -cp "$classes;$testClasspath" org.junit.runner.JUnitCore @tests
if ($LASTEXITCODE -ne 0) { throw "Guide tests failed ($LASTEXITCODE)" }
Write-Host "PNG frames: $frames"
