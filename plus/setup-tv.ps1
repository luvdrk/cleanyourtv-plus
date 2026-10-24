# CleanYourTV Plus: put the whole Plus setup on a TV in one go. Builds the launcher and installs it as Home with the
# remote-button helper (launcher\build-and-install.ps1), turns on the notification access the now playing card
# needs, and pushes a theme (tools\theme.ps1). Run it again for a second TV, or after changing the theme.
#
# Usage: plus\setup-tv.ps1 -TvHost 192.168.1.50 [-Theme plus\sample-theme]
#        Needs what build-and-install.ps1 needs (a JDK 17+, the Android SDK, tools\venv); see the README.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Theme = (Join-Path $PSScriptRoot 'sample-theme')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$Theme = (Resolve-Path $Theme).Path
$py = Join-Path $root 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }

Write-Host '1/3 Building the launcher and installing it as Home'
& (Join-Path $root 'launcher\build-and-install.ps1') -TvHost $TvHost -HomeKeyService

Write-Host '2/3 Notification access for the now playing card (it only reads media sessions)'
$env:TV_HOST = $TvHost
& $py -I (Join-Path $root 'tools\tv.py') 'cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.NowPlayingListener && echo granted'

Write-Host "3/3 Pushing the theme $Theme"
& (Join-Path $root 'tools\theme.ps1') -TvHost $TvHost $Theme

Write-Host 'Done. Press Home on the remote. Undo the launcher with plus\restore-original-ui.ps1.'
