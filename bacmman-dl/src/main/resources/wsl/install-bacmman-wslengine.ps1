<#
.SYNOPSIS
    Installs or updates the WSL distribution used by BACMMAN's WSLEngine: GPU predictions with TensorFlow from the
    Windows version of Fiji, without Docker.

.DESCRIPTION
    1. Checks that WSL2 is available (installs/updates WSL if needed - may need a reboot).
    2. Creates a dedicated WSL distribution (default name "BACMMAN-WSLEngine", Ubuntu 24.04) stored in
       %LOCALAPPDATA%\BACMMAN-WSLEngine. No graphical interface: it only runs the prediction server started by BACMMAN.
    3. Installs python and TensorFlow with its CUDA libraries (pip package tensorflow[and-cuda]) in
       /opt/bacmman-dl/venv. The GPU driver is the WINDOWS NVIDIA driver: nothing else to install.
    4. Checks that TensorFlow sees the GPU.

    Update path: run the installer again. It installs the requested TensorFlow version (-TensorflowVersion) if it
    differs from the installed one. With -Update it also updates Ubuntu packages and python packages.
    -Status prints the installed TensorFlow version without changing anything.

    This script is part of BACMMAN (bacmman-dl, resource /wsl): WSLEngine runs it when the distribution is missing or
    when its TensorFlow version differs from the engine's "TensorFlow Version" parameter.
    In BACMMAN, select the DL engine "WSLEngine" (Option menu > Default DL Engine, or in the configuration).

    Exit codes: 0 success, 1 error, 3 WSL was installed and Windows must be restarted, 10 (-Status) not installed.

.PARAMETER DistroName        Name of the WSL distribution (default: BACMMAN-WSLEngine). Must match the "WSL Distribution"
                             parameter of WSLEngine.
.PARAMETER InstallDir        Windows folder holding the distribution disk (default: %LOCALAPPDATA%\BACMMAN-WSLEngine).
.PARAMETER TensorflowVersion TensorFlow version (default: 2.18.0, the version used by BACMMAN's TF2Engine).
.PARAMETER Update            Also update Ubuntu packages and python packages.
.PARAMETER Status            Only print the installation status: STATUS=ok|not_installed and TENSORFLOW=<version>.
.PARAMETER PauseAtEnd        Wait for Enter before exiting (used when BACMMAN opens the installer in a new window).
.PARAMETER PauseOnError      Wait for Enter before exiting only if the installation failed (automatic installation).
.PARAMETER NoWslUpdate       Do not offer to check for WSL updates.
.PARAMETER Quiet             Never ask questions.
.PARAMETER Uninstall         Remove the WSL distribution.
.PARAMETER Force             Do not ask for confirmation when uninstalling.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install-bacmman-wslengine.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install-bacmman-wslengine.ps1 -Update
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install-bacmman-wslengine.ps1 -Uninstall
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install-bacmman-wslengine.ps1 -Status
#>
[CmdletBinding()]
param(
    [string]$DistroName = "BACMMAN-WSLEngine",
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA "BACMMAN-WSLEngine"),
    [string]$TensorflowVersion = "2.18.0",
    [string]$RootfsBaseUrl = "https://cloud-images.ubuntu.com/wsl/releases/24.04/current",
    [string]$RootfsName = "ubuntu-noble-wsl-amd64-wsl.rootfs.tar.gz",
    [switch]$Update,
    [switch]$Status,
    [switch]$PauseAtEnd,
    [switch]$PauseOnError,
    [switch]$NoWslUpdate,
    [switch]$Quiet,
    [switch]$Uninstall,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$env:WSL_UTF8 = "1"   # make wsl.exe print UTF-8 instead of UTF-16

$EnvVersion    = "1"          # version of the environment layout (venv path, VERSION file), read by WSLEngine
$VenvPython    = "/opt/bacmman-dl/venv/bin/python"
$MarkerFile    = "/opt/bacmman-dl/VERSION"
$MinCudaDriver = [version]"555.85"   # Windows NVIDIA driver for CUDA 12.5 (TensorFlow 2.18)

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Warn([string]$msg) { Write-Host "WARNING: $msg" -ForegroundColor Yellow }
function Exit-Installer([int]$code) {
    if ($PauseAtEnd -or ($PauseOnError -and $code -ne 0 -and $code -ne 3)) { Write-Host ""; Read-Host "Press Enter to close this window" | Out-Null }
    exit $code
}
function Fail([string]$msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; Exit-Installer 1 }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Wait-WithSpinner([Diagnostics.Process]$proc, [string]$activity, [int]$timeoutSec) {
    # animated "| / - \" indicator with elapsed time while a process runs; returns $false on timeout
    $frames = @("|", "/", "-", "\")
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $i = 0
    $timedOut = $false
    $promptNoticed = $false
    while (-not $proc.WaitForExit(250)) {
        # e.g. 'wsl --update' opens its own administrator prompt
        if (-not $promptNoticed -and (Test-AdminPromptOpen)) { Write-AdminPromptNotice; $promptNoticed = $true }
        if ($timeoutSec -gt 0 -and $sw.Elapsed.TotalSeconds -ge $timeoutSec) {
            try { $proc.Kill() } catch { }
            $timedOut = $true
            break
        }
        Write-Host -NoNewline ("`r    {0} {1}... {2:0}s   " -f $frames[$i % 4], $activity, $sw.Elapsed.TotalSeconds)
        $i++
    }
    $status = if ($timedOut) { "timed out" } else { "done" }
    Write-Host ("`r    {0}: {1} ({2:0}s)          " -f $activity, $status, $sw.Elapsed.TotalSeconds)
    return (-not $timedOut)
}

function Test-MsiInstallRunning {
    # Windows Installer holds this mutex while an MSI package (e.g. a WSL update) is being installed
    $m = $null
    try {
        if ([Threading.Mutex]::TryOpenExisting("Global\_MSIExecute", [ref]$m)) { $m.Dispose(); return $true }
    } catch [UnauthorizedAccessException] { return $true }   # exists but owned by an elevated installer
    catch { }
    return $false
}

function Test-AdminPromptOpen {
    # consent.exe is the process that displays the Windows administrator (UAC) prompt
    return [bool](Get-Process -Name consent -ErrorAction SilentlyContinue)
}

function Write-AdminPromptNotice {
    Write-Host ""
    Write-Host "  >>> An ADMINISTRATOR PROMPT is open (it may be hidden behind this window: check the taskbar)." -ForegroundColor Yellow
    Write-Host "  >>> Click 'Yes' to continue. The installer waits until it is answered." -ForegroundColor Yellow
}

function Wait-WslUpdateFinished([int]$timeoutSec = 1200, [int]$idleSec = 10) {
    # 'wsl --update' can return before the WSL package is actually installed (it installs in the
    # background, possibly after an administrator prompt). Installing it shuts WSL down, which would
    # kill the Linux part of this installer: wait until no installation is running.
    $frames = @("|", "/", "-", "\")
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $idleSince = $null
    $promptNoticed = $false
    $i = 0
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        # a pending administrator prompt (possibly left over from a previous run) counts as busy:
        # accepting it later could start a WSL update in the middle of the installation
        $prompt = Test-AdminPromptOpen
        if ($prompt -and -not $promptNoticed) { Write-AdminPromptNotice; $promptNoticed = $true }
        if ($prompt -or (Test-MsiInstallRunning)) { $idleSince = $null }
        elseif (-not $idleSince) { $idleSince = $sw.Elapsed.TotalSeconds }
        # idle for $idleSec s in a row (the installer may start a few seconds after wsl.exe returned)
        if ($idleSince -ne $null -and ($sw.Elapsed.TotalSeconds - $idleSince) -ge $idleSec) { break }
        $what = if ($prompt) { "Waiting for the administrator prompt to be answered" } else { "Waiting for a Windows installation in progress (e.g. WSL update) to finish" }
        Write-Host -NoNewline ("`r    {0} {1}... {2:0}s          " -f $frames[$i % 4], $what, $sw.Elapsed.TotalSeconds)
        $i++
        Start-Sleep -Milliseconds 250
    }
    Write-Host (("`r    No Windows installation in progress ({0:0}s)" -f $sw.Elapsed.TotalSeconds) + (" " * 100))
}

function ConvertTo-ArgumentString([string[]]$arguments) {
    return (($arguments | ForEach-Object {
        if ($_ -match '[\s"]' -or $_ -eq "") { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join " ")
}

function Invoke-Native {
    # Runs a command with a progress indicator. Returns @{ ExitCode; Output } (ExitCode -1 on timeout).
    param([string]$Activity, [string]$Exe, [string[]]$Arguments = @(), [int]$TimeoutSec = 0, [switch]$Elevated)
    if ($Elevated -and -not (Test-Admin)) {
        # elevated processes run in their own window; their output cannot be captured
        Write-AdminPromptNotice
        try {
            $p = Start-Process -FilePath $Exe -ArgumentList $Arguments -Verb RunAs -PassThru
        } catch {
            Write-Warn "The administrator prompt was refused."
            return @{ ExitCode = -2; Output = "" }
        }
        $null = $p.Handle   # keeps ExitCode readable after exit
        $ok = Wait-WithSpinner $p $Activity $TimeoutSec
        $code = if ($ok) { $p.ExitCode } else { -1 }
        return @{ ExitCode = $code; Output = "" }
    }
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ConvertTo-ArgumentString $Arguments
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
    $p = [Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    $ok = Wait-WithSpinner $p $Activity $TimeoutSec
    if (-not $ok) { return @{ ExitCode = -1; Output = "" } }
    $p.WaitForExit()
    $text = ($out.Result + "`n" + $err.Result) -replace "`0", ""
    return @{ ExitCode = $p.ExitCode; Output = $text }
}

function Read-YesNo([string]$question, [bool]$default) {
    $suffix = if ($default) { "[Y/n]" } else { "[y/N]" }
    $ans = Read-Host "$question $suffix"
    if ([string]::IsNullOrWhiteSpace($ans)) { return $default }
    return ($ans -match "^[yYoO]")
}

function Get-WslDistros {
    $r = Invoke-Native -Activity "Listing WSL distributions" -Exe "wsl.exe" -Arguments @("--list", "--quiet") -TimeoutSec 120
    if ($r.ExitCode -ne 0) { return @() }
    # the error stream is appended to Output: keep only plain distribution names
    return @($r.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match "^[\w.\-]+$" })
}

function Get-WslDistroVersion([string]$name) {
    # parses `wsl -l -v`: "* Ubuntu   Running   2"
    $r = Invoke-Native -Activity "Reading WSL distribution versions" -Exe "wsl.exe" -Arguments @("--list", "--verbose") -TimeoutSec 120
    foreach ($line in ($r.Output -split "`r?`n")) {
        $tokens = ($line -replace "^\s*\*?\s*", "").Trim() -split "\s+"
        if ($tokens.Count -ge 3 -and $tokens[0] -eq $name) { return [int]$tokens[-1] }
    }
    return 0
}

function ConvertTo-WslPath([string]$winPath) {
    # default automount root (/mnt)
    $full = [IO.Path]::GetFullPath($winPath)
    $drive = $full.Substring(0, 1).ToLower()
    return "/mnt/$drive" + ($full.Substring(2) -replace "\\", "/")
}


function Get-NvidiaGpu {
    # returns @{ Name; Driver([version]) } of the first NVIDIA GPU, or $null
    try {
        $gpu = Get-CimInstance Win32_VideoController | Where-Object { $_.Name -match "NVIDIA" } | Select-Object -First 1
    } catch { return $null }
    if (-not $gpu) { return $null }
    $drv = $null
    # Windows driver version 32.0.15.5585 -> NVIDIA version 555.85
    $p = "$($gpu.DriverVersion)".Split(".")
    if ($p.Count -eq 4) {
        $digits = ($p[2] + $p[3].PadLeft(4, "0"))
        $digits = $digits.Substring($digits.Length - 5)
        try { $drv = [version]("{0}.{1}" -f [int]$digits.Substring(0, 3), $digits.Substring(3, 2)) } catch { }
    }
    return @{ Name = $gpu.Name; Driver = $drv }
}


# ---------------------------------------------------------------------------------------------
# Script run inside the distribution as root (written with LF line endings).
# Arguments: TENSORFLOW_VERSION ENV_VERSION UPDATE(0|1)
# ---------------------------------------------------------------------------------------------
$ProvisionScript = @'
#!/bin/bash
set -euo pipefail
TF_VERSION="$1"; ENV_VERSION="$2"; UPDATE="$3"
export DEBIAN_FRONTEND=noninteractive
BASE=/opt/bacmman-dl
VENV=$BASE/venv
# finish package installations left half-done by an interrupted previous run
dpkg --configure -a || true

# no systemd, no Windows PATH: the distribution only runs the prediction server
cat > /etc/wsl.conf <<EOF
[boot]
systemd=false
[interop]
enabled=true
appendWindowsPath=false
[automount]
enabled=true
root=/mnt/
EOF

echo "--- installing system packages"
apt-get update -q
if [ "$UPDATE" = "1" ]; then
  echo "--- updating Ubuntu packages"
  apt-get -y -q upgrade
fi
apt-get install -y -q --no-install-recommends ca-certificates python3 python3-venv python3-pip

mkdir -p $BASE
if [ ! -x $VENV/bin/python ]; then
  echo "--- creating the python environment $VENV"
  python3 -m venv $VENV
fi
$VENV/bin/python -m pip install -q --upgrade pip

INSTALLED=$($VENV/bin/python -c "import tensorflow as tf; print(tf.__version__)" 2>/dev/null || true)
if [ "$UPDATE" = "1" ] || [ "$INSTALLED" != "$TF_VERSION" ]; then
  echo "--- installing TensorFlow $TF_VERSION with CUDA libraries (~3 GB download the first time)${INSTALLED:+, replacing $INSTALLED}"
  $VENV/bin/python -m pip install --upgrade "tensorflow[and-cuda]==$TF_VERSION"
else
  echo "--- TensorFlow $TF_VERSION already installed"
fi
echo "env=$ENV_VERSION tensorflow=$TF_VERSION" > $BASE/VERSION

echo "--- checking TensorFlow and the GPU"
$VENV/bin/python - <<'EOF' | tee $BASE/check.txt
import os
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import tensorflow as tf
gpus = tf.config.list_physical_devices("GPU")
print(f"TENSORFLOW={tf.__version__}")
print(f"GPUS={len(gpus)}")
for g in gpus:
    print(f"  {g.name}")
if gpus:  # a small computation on the GPU
    with tf.device("/GPU:0"):
        x = tf.random.uniform((512, 512))
        print("GPU_TEST=" + ("ok" if float(tf.reduce_sum(tf.matmul(x, x))) > 0 else "failed"))
EOF
echo "--- done"
'@

function Get-InstalledTensorflowVersion {
    # reads the package metadata (fast: tensorflow is not imported)
    $r = Invoke-Native -Activity "Reading the TensorFlow version in '$DistroName'" -Exe "wsl.exe" `
        -Arguments @("-d", $DistroName, "-u", "root", "--exec", $VenvPython, "-c", "import importlib.metadata as m; print(m.version('tensorflow'))") -TimeoutSec 300
    if ($r.ExitCode -ne 0) { return $null }
    return ($r.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match "^\d+\.\d+" } | Select-Object -Last 1)
}

# =============================================================================================
# Status
# =============================================================================================
if ($Status) {
    $tfInstalled = $null
    if ((Get-WslDistros) -contains $DistroName) { $tfInstalled = Get-InstalledTensorflowVersion }
    if ($tfInstalled) {
        Write-Output "STATUS=ok"
        Write-Output "TENSORFLOW=$tfInstalled"
        exit 0
    }
    Write-Output "STATUS=not_installed"
    exit 10
}

# =============================================================================================
# Uninstall
# =============================================================================================
if ($Uninstall) {
    if ((Get-WslDistros) -notcontains $DistroName) {
        Write-Host "WSL distribution '$DistroName' is not installed."
        Exit-Installer 0
    }
    # only remove a distribution created by this installer
    $r = Invoke-Native -Activity "Checking '$DistroName'" -Exe "wsl.exe" -Arguments @("-d", $DistroName, "-u", "root", "--", "test", "-f", $MarkerFile) -TimeoutSec 300
    if ($r.ExitCode -ne 0) { Fail "'$DistroName' was not created by this installer ($MarkerFile not found): not removed." }
    if (-not $Force -and -not (Read-YesNo "This will permanently remove the WSL distribution '$DistroName'. Continue?" $false)) { Write-Host "Cancelled."; Exit-Installer 2 }
    $null = Invoke-Native -Activity "Unregistering '$DistroName'" -Exe "wsl.exe" -Arguments @("--unregister", $DistroName)
    Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "WSL distribution '$DistroName' has been removed." -ForegroundColor Green
    Exit-Installer 0
}

# =============================================================================================
# Install: WSL itself
# =============================================================================================
Write-Step "Checking Windows and WSL"
$build = [Environment]::OSVersion.Version.Build
if ($build -lt 19044) {
    Fail "Windows build $build is too old for GPU support in WSL. Windows 10 21H2 (19044) or Windows 11 is required."
}
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) { Fail "wsl.exe not found. Please update Windows." }
if (Test-AdminPromptOpen) { Wait-WslUpdateFinished -idleSec 3 }   # e.g. left over from a previous run

Write-Host "    (the first WSL command can take a minute while the WSL service starts)"
$r = Invoke-Native -Activity "Checking WSL status" -Exe "wsl.exe" -Arguments @("--status") -TimeoutSec 300
if ($r.ExitCode -eq -1) { Fail "WSL did not answer within 5 minutes. Restart Windows and try again." }
if ($r.ExitCode -ne 0) {
    Write-Step "WSL is not installed: installing it (administrator rights required, a separate window opens)"
    $code = (Invoke-Native -Activity "Installing WSL" -Exe "wsl.exe" -Arguments @("--install", "--no-distribution") -Elevated).ExitCode
    if ($code -ne 0) { $code = (Invoke-Native -Activity "Installing WSL" -Exe "wsl.exe" -Arguments @("--install") -Elevated).ExitCode }
    if ($code -ne 0) { Fail "WSL installation failed (exit code $code). Make sure virtualization is enabled in the BIOS." }
    Write-Host ""
    Write-Host "WSL has been installed. Please RESTART Windows, then run this installer again." -ForegroundColor Green
    Exit-Installer 3
}

# The Store version of WSL (the one that supports 'wsl --version') is required for GPU support on Windows 10
$r = Invoke-Native -Activity "Checking WSL version" -Exe "wsl.exe" -Arguments @("--version") -TimeoutSec 120
if ($r.ExitCode -ne 0) {
    Write-Step "Updating WSL to the Store version (needed for GPU support; administrator rights required)"
    $null = Invoke-Native -Activity "Updating WSL (can take several minutes)" -Exe "wsl.exe" -Arguments @("--update") -Elevated
    Wait-WslUpdateFinished
    $r = Invoke-Native -Activity "Checking WSL version" -Exe "wsl.exe" -Arguments @("--version") -TimeoutSec 120
    if ($r.ExitCode -ne 0) { Fail "Could not update WSL. Run 'wsl --update' in an administrator terminal, then retry." }
} elseif (-not $NoWslUpdate -and ($Quiet -or (Read-YesNo "    Check for WSL updates now? (recommended, can take several minutes)" $true))) {
    Write-Host "    If Windows asks for administrator rights, accept: the installer waits for the update to finish."
    $u = Invoke-Native -Activity "Updating WSL if needed (can take several minutes)" -Exe "wsl.exe" -Arguments @("--update") -TimeoutSec 900
    Wait-WslUpdateFinished
    if ($u.ExitCode -ne 0) { Write-Warn "WSL update skipped (exit code $($u.ExitCode)); continuing with the installed version." }
}

# =============================================================================================
# Install: GPU check on Windows
# =============================================================================================
$gpu = Get-NvidiaGpu
if (-not $gpu) {
    Write-Warn "No NVIDIA GPU detected on Windows: predictions will run on the CPU."
    if (-not $Quiet -and -not (Read-YesNo "    Continue anyway?" $false)) { Exit-Installer 2 }
} else {
    Write-Host "    NVIDIA GPU: $($gpu.Name), driver $($gpu.Driver)"
    if ($gpu.Driver -and $gpu.Driver -lt $MinCudaDriver) {
        Write-Warn "NVIDIA driver $($gpu.Driver) is older than $MinCudaDriver. Update it from https://www.nvidia.com/drivers (on Windows; never install a Linux driver in WSL)."
    }
}

# =============================================================================================
# Install: the distribution
# =============================================================================================
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
$distros = Get-WslDistros
if ($distros -notcontains $DistroName) {
    Write-Step "Creating WSL distribution '$DistroName' (Ubuntu 24.04)"
    $tarball = Join-Path $InstallDir $RootfsName
    & curl.exe -fL --retry 3 --progress-bar -o $tarball "$RootfsBaseUrl/$RootfsName"
    if ($LASTEXITCODE -ne 0) { Fail "Download of $RootfsBaseUrl/$RootfsName failed." }
    try {
        $sums = (& curl.exe -fsL "$RootfsBaseUrl/SHA256SUMS") -split "`n"
        $expected = ($sums | Where-Object { $_ -match [regex]::Escape($RootfsName) } | Select-Object -First 1) -split "\s+" | Select-Object -First 1
        if ($expected) {
            Write-Host "    Verifying checksum..."
            $actual = (Get-FileHash $tarball -Algorithm SHA256).Hash
            if ($actual -ne $expected.ToUpper()) { Remove-Item $tarball -Force; Fail "Checksum mismatch for $RootfsName." }
        }
    } catch { Write-Warn "Could not verify the rootfs checksum: $_" }
    $r = Invoke-Native -Activity "Creating the WSL distribution" -Exe "wsl.exe" `
        -Arguments @("--import", $DistroName, (Join-Path $InstallDir "disk"), $tarball, "--version", "2")
    Remove-Item $tarball -Force -ErrorAction SilentlyContinue
    if ($r.ExitCode -ne 0) {
        Write-Host $r.Output
        Fail "wsl --import failed. Is virtualization (VT-x/AMD-V) enabled in the BIOS?"
    }
} else {
    if ((Get-WslDistroVersion $DistroName) -ne 2) { Fail "'$DistroName' is a WSL1 distribution: GPU support needs WSL2. Convert it with: wsl --set-version $DistroName 2" }
    $mode = if ($Update) { "updating" } else { "checking" }
    Write-Step "WSL distribution '$DistroName' exists: $mode it"
}

# =============================================================================================
# Install: python + TensorFlow inside the distribution
# =============================================================================================
Write-Step "Installing TensorFlow $TensorflowVersion in '$DistroName'"
$tfInstalled = Get-InstalledTensorflowVersion
if ($tfInstalled) { Write-Host "    installed version: $tfInstalled" }
$provisionWin = Join-Path $InstallDir "provision.sh"
[IO.File]::WriteAllText($provisionWin, ($ProvisionScript -replace "`r`n", "`n"), (New-Object Text.UTF8Encoding $false))
$updateArg = if ($Update) { "1" } else { "0" }
# a WSL update installing in the background would shut WSL down in the middle of the installation
Wait-WslUpdateFinished -idleSec 3
& wsl.exe -d $DistroName -u root -- bash (ConvertTo-WslPath $provisionWin) $TensorflowVersion $EnvVersion $updateArg
$code = $LASTEXITCODE
Remove-Item $provisionWin -Force -ErrorAction SilentlyContinue
if ($code -ne 0) {
    Fail ("Installation inside '$DistroName' failed (see messages above). If the output stops abruptly, " +
          "WSL was restarted (e.g. by a WSL update): run the installer again, it resumes where it stopped.")
}
$check = Invoke-Native -Activity "Reading the GPU check" -Exe "wsl.exe" -Arguments @("-d", $DistroName, "-u", "root", "--", "cat", "/opt/bacmman-dl/check.txt") -TimeoutSec 120
# restart the distribution so that /etc/wsl.conf is taken into account
$null = Invoke-Native -Activity "Restarting '$DistroName'" -Exe "wsl.exe" -Arguments @("--terminate", $DistroName) -TimeoutSec 120

$nGpus = ($check.Output -split "`r?`n" | Where-Object { $_ -match "^GPUS=(\d+)" } | ForEach-Object { [int]$Matches[1] } | Select-Object -First 1)
Write-Host ""
Write-Host "The WSL distribution '$DistroName' is ready (TensorFlow $TensorflowVersion)." -ForegroundColor Green
if ($nGpus -gt 0) {
    Write-Host "  - GPU(s) visible from TensorFlow: $nGpus"
} else {
    Write-Warn "TensorFlow does not see any GPU in WSL: predictions will run on the CPU. Check the Windows NVIDIA driver, then run: wsl -d $DistroName -- nvidia-smi"
}
Write-Host "  - In BACMMAN, select the DL engine 'WSLEngine' (Option menu > Default DL Engine, or in the configuration)."
Write-Host "  - Update: run this installer again (with -Update to also update Ubuntu and python packages)."
Write-Host "  - Uninstall: powershell -ExecutionPolicy Bypass -File install-bacmman-wslengine.ps1 -Uninstall"
Exit-Installer 0
