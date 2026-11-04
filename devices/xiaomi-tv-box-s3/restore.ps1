# Re-enable every package in debloat.txt and put the stock Google TV home back.
# Usage:  .\restore.ps1 -TvHost 192.168.1.50   (USB debugging must be on and this PC allowed)
param([Parameter(Mandatory = $true)][string]$TvHost)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$s = "${TvHost}:5555"
& $adb connect $s | Out-Null
$pkgs = Get-Content (Join-Path $PSScriptRoot 'debloat.txt') |
    ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
foreach ($p in $pkgs) { & $adb -s $s shell "pm enable $p" }
& $adb -s $s shell 'pm enable com.google.android.apps.tv.launcherx'
& $adb -s $s shell 'cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity'
"Done. Disabled now:"; & $adb -s $s shell 'pm list packages -d'
