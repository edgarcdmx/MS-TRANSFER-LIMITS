<#
.SYNOPSIS
  Windows bootstrapper for MS-TRANSFER-LIMITS: ensures Git for Windows (bash) and optionally
  Docker Desktop, then runs install.sh (installer + end-to-end demo + report) with Git Bash.

.DESCRIPTION
  Compatible with Windows PowerShell 5.1 and PowerShell 7. Without -InstallPrerequisites it never
  installs anything: it only prints the winget command to run.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Mode local
  powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Mode local -SkipTests -KeepRunning
  powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -Stop
  powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 -InstallPrerequisites -InstallDocker

.NOTES
  One-liner on a fresh machine (after installing Git):
    git clone https://github.com/edgarcdmx/MS-TRANSFER-LIMITS.git; cd MS-TRANSFER-LIMITS;
    powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1
#>
[CmdletBinding()]
param(
  [ValidateSet('auto', 'docker', 'local')]
  [string]$Mode = 'auto',
  [switch]$SkipTests,
  [switch]$KeepRunning,
  [switch]$Stop,
  [int]$Port = 0,
  [int]$Retries = 0,
  [int]$Concurrency = 0,
  [string]$Report = '',
  [switch]$AiFix,
  [switch]$InstallPrerequisites,
  [switch]$InstallDocker,
  [switch]$Help,
  # Extra arguments passed verbatim to install.sh.
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$PassThru = @()
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Write-Step([string]$msg) { Write-Host "[install.ps1] $msg" -ForegroundColor Cyan }
function Write-Warn([string]$msg) { Write-Host "[install.ps1] $msg" -ForegroundColor Yellow }

function Test-Winget { return [bool](Get-Command winget -ErrorAction SilentlyContinue) }

function Update-SessionPath {
  $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
  $user = [Environment]::GetEnvironmentVariable('Path', 'User')
  $env:Path = "$machine;$user"
}

function Find-GitBash {
  $candidates = New-Object System.Collections.Generic.List[string]
  $git = Get-Command git -ErrorAction SilentlyContinue
  if ($git) {
    # ...\Git\cmd\git.exe or ...\Git\bin\git.exe or ...\Git\mingw64\bin\git.exe
    $dir = Split-Path -Parent $git.Source
    foreach ($rel in @('..\bin\bash.exe', 'bash.exe', '..\..\bin\bash.exe')) {
      $candidates.Add((Join-Path $dir $rel))
    }
  }
  foreach ($root in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, (Join-Path $env:LOCALAPPDATA 'Programs'))) {
    if ($root) { $candidates.Add((Join-Path $root 'Git\bin\bash.exe')) }
  }
  foreach ($c in $candidates) {
    if (Test-Path -LiteralPath $c) { return (Resolve-Path -LiteralPath $c).Path }
  }
  return $null
}

function Install-WithWinget([string]$id, [string]$name) {
  if (-not (Test-Winget)) {
    throw "winget is not available. Install $name manually (https://git-scm.com/download/win or https://www.docker.com/products/docker-desktop/) and re-run."
  }
  Write-Step "Installing $name with winget ($id)..."
  & winget install --id $id -e --source winget --accept-package-agreements --accept-source-agreements
  if ($LASTEXITCODE -ne 0) { throw "winget install $id failed (exit $LASTEXITCODE)" }
  Update-SessionPath
}

# ---------------------------------------------------------------------------- Git Bash ---------
$bash = Find-GitBash
if (-not $bash) {
  if ($InstallPrerequisites) {
    Install-WithWinget 'Git.Git' 'Git for Windows'
    $bash = Find-GitBash
  }
  if (-not $bash) {
    Write-Warn 'Git for Windows (bash.exe) was not found.'
    Write-Warn 'Install it with:  winget install --id Git.Git -e --source winget'
    Write-Warn 'or re-run this script with -InstallPrerequisites.'
    exit 2
  }
}
Write-Step "Git Bash: $bash"

# ---------------------------------------------------------------------------- Docker -----------
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
  if ($InstallDocker) {
    Install-WithWinget 'Docker.DockerDesktop' 'Docker Desktop'
    Write-Warn 'Docker Desktop installed: start it (and finish its first-run setup) before using -Mode docker.'
    Write-Warn 'A sign-out/reboot may be required for WSL2. Until then -Mode auto falls back to local mode.'
  } elseif ($Mode -eq 'docker') {
    Write-Warn 'Docker is not installed. Install it with -InstallDocker or:'
    Write-Warn '  winget install --id Docker.DockerDesktop -e --source winget'
    exit 2
  } else {
    Write-Step 'Docker not found: install.sh will use local mode (portable JDK 27 + embedded PostgreSQL).'
  }
}

# ---------------------------------------------------------------------------- run install.sh ---
$script = Join-Path $here 'install.sh'
if (-not (Test-Path -LiteralPath $script)) { throw "install.sh not found next to install.ps1 ($here)" }

$shArgs = New-Object System.Collections.Generic.List[string]
if ($Help) { $shArgs.Add('--help') }
elseif ($Stop) { $shArgs.Add('--stop') }
else {
  $shArgs.Add('--mode'); $shArgs.Add($Mode)
  if ($SkipTests) { $shArgs.Add('--skip-tests') }
  if ($KeepRunning) { $shArgs.Add('--keep-running') }
  if ($Port -gt 0) { $shArgs.Add('--port'); $shArgs.Add([string]$Port) }
  if ($Retries -gt 0) { $shArgs.Add('--retries'); $shArgs.Add([string]$Retries) }
  if ($Concurrency -gt 0) { $shArgs.Add('--concurrency'); $shArgs.Add([string]$Concurrency) }
  if ($Report) { $shArgs.Add('--report'); $shArgs.Add(($Report -replace '\\', '/')) }
  if ($AiFix) { $shArgs.Add('--ai-fix') }
}
foreach ($a in $PassThru) { if ($a) { $shArgs.Add($a) } }

$scriptForBash = $script -replace '\\', '/'
Write-Step ("bash install.sh " + ($shArgs -join ' '))
Push-Location $here
try {
  & $bash $scriptForBash @($shArgs.ToArray())
  $code = $LASTEXITCODE
} finally {
  Pop-Location
}
Write-Step "install.sh finished with exit code $code"
exit $code
