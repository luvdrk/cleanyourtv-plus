# CleanYourTV Plus: put the TV's own home screen back and remove the launcher. The stock launcher is turned back on
# and made Home first, so the TV is never left without one; then the remote-button helper goes off the
# accessibility list and the launcher is uninstalled. Apps a debloat list disabled come back with tools\restore.sh.
#
# Usage: plus\restore-original-ui.ps1 -TvHost 192.168.1.50 [-StockLauncher com.google.android.tvlauncher]
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$StockLauncher = 'com.google.android.tvlauncher'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$py = Join-Path $root 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
$env:TV_HOST = $TvHost
$tv = Join-Path $root 'tools\tv.py'

& $py -I $tv "pm enable --user 0 $StockLauncher"
# set-home-activity needs package/activity on Android 9, so resolve the stock launcher's Home component on the TV.
& $py -I $tv "cmd package set-home-activity --user 0 `$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $StockLauncher | tail -n 1)"
# Only the launcher's own helper comes off the list; other accessibility services stay on.
's=$(settings get secure enabled_accessibility_services); s=$(echo "$s" | tr ":" "\n" | grep -v "^com.gathin.tvhome/" | paste -sd ":" -); if [ -z "$s" ]; then settings delete secure enabled_accessibility_services; else settings put secure enabled_accessibility_services "$s"; fi; echo helper off' |
    & $py -I $tv --batch
& $py -I $tv 'pm uninstall com.gathin.tvhome; input keyevent KEYCODE_HOME'
Write-Host "Done: $StockLauncher is Home again."
