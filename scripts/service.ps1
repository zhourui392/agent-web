# @author zhourui(V33215020)

param(
    [string]$Command = "start",
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ApplicationArguments
)

$ErrorActionPreference = "Stop"
$RequiredJavaMajor = 21

function Get-JavaMajorVersion {
    param([string]$JavaExecutable)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $JavaExecutable
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $versionProcess = [System.Diagnostics.Process]::Start($startInfo)
    $versionOutput = $versionProcess.StandardError.ReadToEnd() + $versionProcess.StandardOutput.ReadToEnd()
    $versionProcess.WaitForExit()
    if ($versionProcess.ExitCode -ne 0) {
        return $null
    }
    if ($versionOutput -match 'version\s+"(?<major>\d+)') {
        return [int]$Matches.major
    }
    return $null
}

function Get-JdkHomeFromJava {
    param([string]$JavaCandidate)

    if ([string]::IsNullOrWhiteSpace($JavaCandidate)) {
        return $null
    }

    $javaPath = $null
    if (Test-Path -LiteralPath $JavaCandidate -PathType Leaf) {
        $javaPath = (Resolve-Path -LiteralPath $JavaCandidate).Path
    } else {
        $javaCommand = Get-Command $JavaCandidate -ErrorAction SilentlyContinue
        if ($null -ne $javaCommand) {
            $javaPath = $javaCommand.Source
        }
    }
    if ([string]::IsNullOrWhiteSpace($javaPath)) {
        return $null
    }

    $binDirectory = Split-Path -Parent $javaPath
    $jdkHome = Split-Path -Parent $binDirectory
    if (-not (Test-Path -LiteralPath (Join-Path $jdkHome "bin\javac.exe") -PathType Leaf)) {
        return $null
    }
    return $jdkHome
}

function Get-RegistryJdkHomes {
    $registryRoots = @(
        "HKLM:\SOFTWARE\JavaSoft\JDK",
        "HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK"
    )
    foreach ($registryRoot in $registryRoots) {
        if (-not (Test-Path $registryRoot)) {
            continue
        }
        foreach ($versionKey in Get-ChildItem $registryRoot -ErrorAction SilentlyContinue) {
            $javaHome = (Get-ItemProperty $versionKey.PSPath -Name JavaHome -ErrorAction SilentlyContinue).JavaHome
            if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
                $javaHome
            }
        }
    }
}

function Find-Jdk {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_BIN)) {
        $explicitHome = Get-JdkHomeFromJava $env:JAVA_BIN
        if ($null -eq $explicitHome) {
            throw "JAVA_BIN does not point to a complete JDK: $($env:JAVA_BIN)"
        }
        $explicitMajor = Get-JavaMajorVersion (Join-Path $explicitHome "bin\java.exe")
        if ($null -eq $explicitMajor -or $explicitMajor -lt $RequiredJavaMajor) {
            throw "JAVA_BIN requires JDK $RequiredJavaMajor or later, but found JDK $explicitMajor at $explicitHome"
        }
        return $explicitHome
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe"))
    }
    $pathJava = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($null -ne $pathJava) {
        $candidates.Add($pathJava.Source)
    }
    foreach ($registryHome in Get-RegistryJdkHomes) {
        $candidates.Add((Join-Path $registryHome "bin\java.exe"))
    }

    $searchRoots = @(
        (Join-Path $env:ProgramFiles "Java"),
        (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
        (Join-Path $env:ProgramFiles "Microsoft"),
        (Join-Path $env:ProgramFiles "Amazon Corretto"),
        (Join-Path $env:ProgramFiles "Zulu"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"),
        (Join-Path $env:USERPROFILE ".jdks")
    )
    foreach ($searchRoot in $searchRoots) {
        if ([string]::IsNullOrWhiteSpace($searchRoot) -or -not (Test-Path -LiteralPath $searchRoot -PathType Container)) {
            continue
        }
        foreach ($jdkDirectory in Get-ChildItem -LiteralPath $searchRoot -Directory -ErrorAction SilentlyContinue) {
            $candidates.Add((Join-Path $jdkDirectory.FullName "bin\java.exe"))
        }
    }

    $visited = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $fallbackHome = $null
    $fallbackMajor = $null
    foreach ($candidate in $candidates) {
        $jdkHome = Get-JdkHomeFromJava $candidate
        if ($null -eq $jdkHome -or -not $visited.Add($jdkHome)) {
            continue
        }
        $major = Get-JavaMajorVersion (Join-Path $jdkHome "bin\java.exe")
        if ($null -eq $major) {
            continue
        }
        if ($major -eq $RequiredJavaMajor) {
            return $jdkHome
        }
        if ($major -gt $RequiredJavaMajor -and $null -eq $fallbackHome) {
            $fallbackHome = $jdkHome
            $fallbackMajor = $major
        }
    }

    if ($null -ne $fallbackHome) {
        Write-Warning "JDK $RequiredJavaMajor was not found; using compatible JDK $fallbackMajor at $fallbackHome"
        return $fallbackHome
    }
    throw "No complete JDK $RequiredJavaMajor or later was found. Install JDK $RequiredJavaMajor, or set JAVA_BIN to its java.exe."
}

function Set-JdkEnvironment {
    Write-Host "[1/3] Locating an installed JDK $RequiredJavaMajor or later..."
    $jdkHome = Find-Jdk
    $env:JAVA_HOME = $jdkHome
    $env:Path = (Join-Path $jdkHome "bin") + [System.IO.Path]::PathSeparator + $env:Path
    Write-Host "Using JAVA_HOME=$jdkHome"
}

$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDirectory = (Resolve-Path (Join-Path $ScriptDirectory "..")).Path
$AppDirectory = Join-Path $ProjectDirectory "app"
$RuntimeJar = Join-Path $AppDirectory "agent-web.jar"
$PidFile = Join-Path $AppDirectory "agent-web.pid"
$LogDirectory = Join-Path $ProjectDirectory "logs"
$StandardLog = Join-Path $LogDirectory "service.log"
$ErrorLog = Join-Path $LogDirectory "service-error.log"

function Find-Maven {
    $wrapper = Join-Path $ProjectDirectory "mvnw.cmd"
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) {
        return $wrapper
    }
    $maven = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if ($null -eq $maven) {
        $maven = Get-Command "mvn" -ErrorAction SilentlyContinue
    }
    if ($null -eq $maven) {
        throw "Maven was not found. Install Maven 3.6 or later and add mvn to PATH."
    }
    return $maven.Source
}

function Build-Application {
    $maven = Find-Maven
    Write-Host "[2/3] Building the application with Maven..."
    Push-Location $ProjectDirectory
    try {
        & $maven clean package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    $artifacts = @(Get-ChildItem -LiteralPath (Join-Path $ProjectDirectory "target") -Filter "agent-web-*.jar" -File)
    if ($artifacts.Count -ne 1) {
        throw "Expected one application JAR in target/, but found $($artifacts.Count)."
    }
    New-Item -ItemType Directory -Path $AppDirectory -Force | Out-Null
    Copy-Item -LiteralPath $artifacts[0].FullName -Destination $RuntimeJar -Force
    Write-Host "Build complete: $RuntimeJar"
}

function Get-ManagedProcess {
    if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
        return $null
    }
    $pidText = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    $processId = 0
    if (-not [int]::TryParse($pidText, [ref]$processId)) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        return $null
    }
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -eq $process -or $process.ProcessName -notin @("java", "javaw")) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        return $null
    }
    $processInfo = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($null -ne $processInfo `
        -and -not [string]::IsNullOrWhiteSpace($processInfo.CommandLine) `
        -and $processInfo.CommandLine.IndexOf(
            $RuntimeJar,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -lt 0) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        return $null
    }
    return $process
}

function ConvertTo-ProcessArgument {
    param([string]$Value)

    if ([string]::IsNullOrEmpty($Value)) {
        return '""'
    }
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Get-JavaOptions {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_OPTS)) {
        return @()
    }
    return @($env:JAVA_OPTS -split '\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Start-Application {
    param([string[]]$Arguments)

    $runningProcess = Get-ManagedProcess
    if ($null -ne $runningProcess) {
        Write-Host "agent-web is already running (PID $($runningProcess.Id))."
        return
    }

    New-Item -ItemType Directory -Path $AppDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    $javaExecutable = Join-Path $env:JAVA_HOME "bin\java.exe"
    $processArguments = @()
    $processArguments += Get-JavaOptions
    $processArguments += @("-jar", $RuntimeJar)
    if ($null -ne $Arguments) {
        $processArguments += $Arguments
    }
    $escapedArguments = @($processArguments | ForEach-Object { ConvertTo-ProcessArgument $_ })

    Write-Host "[3/3] Starting agent-web..."
    $process = Start-Process -FilePath $javaExecutable `
        -ArgumentList $escapedArguments `
        -WorkingDirectory $ProjectDirectory `
        -RedirectStandardOutput $StandardLog `
        -RedirectStandardError $ErrorLog `
        -PassThru
    Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ASCII
    Start-Sleep -Seconds 2
    $process.Refresh()
    if ($process.HasExited) {
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        throw "agent-web exited during startup. Check $StandardLog and $ErrorLog"
    }
    Write-Host "agent-web started (PID $($process.Id)). Logs: $StandardLog, $ErrorLog"
}

function Start-ServiceWithBuild {
    param([string[]]$Arguments)

    $runningProcess = Get-ManagedProcess
    if ($null -ne $runningProcess) {
        Write-Host "agent-web is already running (PID $($runningProcess.Id))."
        return
    }
    Build-Application
    Start-Application $Arguments
}

function Stop-Application {
    $process = Get-ManagedProcess
    if ($null -eq $process) {
        Write-Host "agent-web is not running."
        return
    }

    Write-Host "Stopping agent-web (PID $($process.Id))..."
    Stop-Process -Id $process.Id
    try {
        Wait-Process -Id $process.Id -Timeout 30 -ErrorAction Stop
    } catch {
        Write-Warning "Graceful shutdown timed out; forcing PID $($process.Id) to stop."
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host "agent-web stopped."
}

function Show-Status {
    $process = Get-ManagedProcess
    if ($null -eq $process) {
        Write-Host "agent-web is not running."
        return
    }
    Write-Host "agent-web is running (PID $($process.Id))."
}

function Show-Logs {
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    if (-not (Test-Path -LiteralPath $StandardLog)) {
        New-Item -ItemType File -Path $StandardLog | Out-Null
    }
    Get-Content -LiteralPath $StandardLog -Tail 200 -Wait
}

function Show-Usage {
    @"
Usage: .\scripts\service.ps1 {build|start|stop|restart|status|logs} [application arguments]

Commands:
  build     Locate JDK 21+ and run Maven clean package.
  start     Build, then start agent-web in the background.
  stop      Stop the background agent-web process.
  restart   Stop, rebuild, and start agent-web.
  status    Show whether the managed process is running.
  logs      Follow logs/service.log.

Environment:
  JAVA_BIN  Explicit java.exe used to locate the JDK.
  JAVA_OPTS JVM options, for example: -Xms512m -Xmx2g
"@ | Write-Host
}

switch ($Command.ToLowerInvariant()) {
    "build" {
        Set-JdkEnvironment
        Build-Application
    }
    "start" {
        Set-JdkEnvironment
        Start-ServiceWithBuild $ApplicationArguments
    }
    "stop" {
        Stop-Application
    }
    "restart" {
        Set-JdkEnvironment
        Stop-Application
        Build-Application
        Start-Application $ApplicationArguments
    }
    "status" {
        Show-Status
    }
    "logs" {
        Show-Logs
    }
    { $_ -in @("help", "-h", "--help") } {
        Show-Usage
    }
    default {
        Show-Usage
        throw "Unknown command: $Command"
    }
}
