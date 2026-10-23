# Copy the Ruby Wars server's world backups to this PC (an off-machine copy, in case the VM's disk dies).
# Copies any backup zips it doesn't have yet and keeps the newest $Keep. Needs the SSH key set up by
# vm-trust-this-pc.ps1. Run by hand, or let the "Ruby Wars backups" scheduled task run it daily:
#   powershell -ExecutionPolicy Bypass -File tools\pull-backups.ps1 [-Install]
param(
	[string]$Server = "gregrose0929@192.168.0.115",
	[string]$Dest = (Join-Path ([Environment]::GetFolderPath("MyDocuments")) "Ruby Wars backups"),
	[int]$Keep = 7,
	[switch]$Install
)

if ($Install) {
	# A daily task (and one at logon, so a PC that was off still catches up). Runs only while you're logged in.
	$action = New-ScheduledTaskAction -Execute "powershell.exe" `
		-Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`""
	$triggers = @((New-ScheduledTaskTrigger -Daily -At 7am), (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME))
	$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 1)
	Register-ScheduledTask -TaskName "Ruby Wars backups" -Action $action -Trigger $triggers -Settings $settings `
		-Description "Copies the Ruby Wars server's world backups to $Dest" -Force | Out-Null
	Write-Output "Installed the 'Ruby Wars backups' task (daily at 7 AM and at logon)."
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$log = Join-Path $Dest "pull.log"
function Log($text) { Add-Content -Path $log -Value ("{0:yyyy-MM-dd HH:mm}  {1}" -f (Get-Date), $text); Write-Output $text }

$remote = ssh -o BatchMode=yes -o ConnectTimeout=15 $Server "ls -1 /opt/rubywars/server/backups/ 2>/dev/null"
if ($LASTEXITCODE -ne 0) {
	Log "Couldn't reach the server ($Server); will try again next time."
	exit 1
}
$copied = 0
foreach ($name in $remote) {
	if ($name -notlike "*.zip") { continue }
	$target = Join-Path $Dest $name
	if (Test-Path $target) { continue }
	scp -q -o BatchMode=yes "${Server}:/opt/rubywars/server/backups/$name" "$target.part"
	if ($LASTEXITCODE -eq 0) {
		Move-Item -Force "$target.part" $target
		$copied++
	} else {
		Remove-Item -ErrorAction SilentlyContinue "$target.part"
		Log "Failed to copy $name"
	}
}
# Keep the newest $Keep.
Get-ChildItem -Path $Dest -Filter *.zip | Sort-Object LastWriteTime -Descending | Select-Object -Skip $Keep | Remove-Item -Force
Log "Copied $copied new backup(s); $((Get-ChildItem -Path $Dest -Filter *.zip).Count) kept in $Dest"
