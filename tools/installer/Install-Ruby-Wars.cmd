<# :
@echo off
rem Ruby Wars installer and updater. Double-click to install; the "Ruby Wars" desktop shortcut it makes runs it again
rem with "play" (update if there's something new, then open the Minecraft Launcher).
title Ruby Wars
set "RW_SELF=%~f0"
set "RW_MODE=%~1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex (Get-Content -Raw -LiteralPath $env:RW_SELF)"
exit /b
#>

# ---------------------------------------------------------------------------------------------------------------------
# Everything below is PowerShell (Windows PowerShell 5.1, which every Windows 10/11 PC has).
#
# What it does:
#   1. Finds the newest Ruby Wars pack on GitHub (the same .mrpack the Modrinth App uses).
#   2. Puts its mods in their own game folder (%APPDATA%\.rubywars), so nothing else you play is touched.
#   3. Adds Fabric and a "Ruby Wars" entry to the official Minecraft Launcher.
#   4. Puts a "Ruby Wars" shortcut on the desktop: it checks for a new pack, then opens the launcher.
#   5. The first time, gives the game sensible settings (the player's own if they have some).
#
# For testing: RW_MCDIR / RW_GAMEDIR point it at other folders, RW_NOLAUNCH=1 stops it opening the launcher or
# waiting for a key, RW_NOSHORTCUT=1 leaves the desktop alone.
# ---------------------------------------------------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = 'gregrose0929/ruby-wars'
$Mode = $env:RW_MODE
$Self = $env:RW_SELF
$McDir = if ($env:RW_MCDIR) { $env:RW_MCDIR } else { Join-Path $env:APPDATA '.minecraft' }
$GameDir = if ($env:RW_GAMEDIR) { $env:RW_GAMEDIR } else { Join-Path $env:APPDATA '.rubywars' }
$Quiet = [bool]$env:RW_NOLAUNCH
$Utf8 = New-Object Text.UTF8Encoding $false

function Say([string]$text, [string]$color = 'White') {
	Write-Host $text -ForegroundColor $color
}

function Pause-End([string]$text) {
	if (-not $Quiet) {
		Write-Host ''
		Read-Host $text | Out-Null
	}
}

# The newest release's tag (GitHub redirects /releases/latest to it), or $null when offline.
function Get-LatestTag {
	try {
		$request = [Net.HttpWebRequest]::Create("https://github.com/$Repo/releases/latest")
		$request.AllowAutoRedirect = $false
		$request.Method = 'HEAD'
		$request.UserAgent = 'RubyWarsInstaller'
		$request.Timeout = 8000
		$response = $request.GetResponse()
		$location = $response.Headers['Location']
		$response.Close()
		if ($location -match '/tag/([^/?#]+)') {
			return $Matches[1]
		}
	} catch {
	}
	return $null
}

function Get-Sha1([string]$path) {
	return (Get-FileHash -LiteralPath $path -Algorithm SHA1).Hash.ToLowerInvariant()
}

# A path from the pack, made safe: inside the game folder, never outside it.
function Resolve-PackPath([string]$relative) {
	if ($relative -match '\.\.' -or $relative -match '^[\\/]' -or $relative -match ':') {
		throw "The pack has a file path that isn't allowed: $relative"
	}
	return Join-Path $GameDir ($relative -replace '/', '\')
}

# Download the pack for $tag and make the game folder match it. Returns the pack's Minecraft and Fabric versions.
function Sync-Pack([string]$tag) {
	New-Item -ItemType Directory -Force -Path $GameDir | Out-Null
	$pack = Join-Path $env:TEMP "rubywars-$tag.mrpack"
	Say '  Downloading the Ruby Wars pack...'
	Invoke-WebRequest "https://github.com/$Repo/releases/download/$tag/ruby-wars.mrpack" -OutFile $pack -UseBasicParsing
	Add-Type -AssemblyName System.IO.Compression.FileSystem
	$zip = [IO.Compression.ZipFile]::OpenRead($pack)
	$keep = New-Object System.Collections.Generic.List[string]
	try {
		$reader = New-Object IO.StreamReader($zip.GetEntry('modrinth.index.json').Open())
		$index = $reader.ReadToEnd() | ConvertFrom-Json
		$reader.Close()
		$n = 0
		foreach ($file in $index.files) {
			if ($file.env -and $file.env.client -eq 'unsupported') {
				continue
			}
			$n++
			$target = Resolve-PackPath $file.path
			$keep.Add($file.path)
			if ((Test-Path -LiteralPath $target) -and (Get-Sha1 $target) -eq $file.hashes.sha1.ToLowerInvariant()) {
				continue
			}
			Say ("  Getting " + (Split-Path $file.path -Leaf) + '...')
			New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
			$part = "$target.part"
			Invoke-WebRequest $file.downloads[0] -OutFile $part -UseBasicParsing
			if ((Get-Sha1 $part) -ne $file.hashes.sha1.ToLowerInvariant()) {
				Remove-Item -LiteralPath $part -Force
				throw "A download didn't arrive whole: $($file.path). Try again in a minute."
			}
			Move-Item -LiteralPath $part -Destination $target -Force
		}
		foreach ($entry in $zip.Entries) {
			if (-not $entry.FullName.StartsWith('overrides/') -or $entry.FullName.EndsWith('/')) {
				continue
			}
			$relative = $entry.FullName.Substring(10)
			$target = Resolve-PackPath $relative
			New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
			[IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
			$keep.Add($relative)
		}
	} finally {
		$zip.Dispose()
	}
	Remove-Item -LiteralPath $pack -Force -ErrorAction SilentlyContinue
	# Mods an older pack put here that the new one doesn't have: gone (two versions of a mod won't start).
	$list = Join-Path $GameDir 'rubywars-files.txt'
	if (Test-Path -LiteralPath $list) {
		foreach ($old in Get-Content -LiteralPath $list) {
			if ($old -and -not $keep.Contains($old)) {
				Remove-Item -LiteralPath (Resolve-PackPath $old) -Force -ErrorAction SilentlyContinue
			}
		}
	}
	[IO.File]::WriteAllLines($list, $keep, $Utf8)
	[IO.File]::WriteAllText((Join-Path $GameDir 'rubywars-version.txt'), $tag, $Utf8)
	return @{ Minecraft = $index.dependencies.minecraft; Loader = $index.dependencies.'fabric-loader' }
}

# Fabric, as a version the Minecraft Launcher can start (the launcher downloads the rest itself).
function Install-Fabric([string]$minecraft, [string]$loader) {
	$id = "fabric-loader-$loader-$minecraft"
	$dir = Join-Path $McDir "versions\$id"
	$json = Join-Path $dir "$id.json"
	if (-not (Test-Path -LiteralPath $json)) {
		Say '  Adding Fabric...'
		New-Item -ItemType Directory -Force -Path $dir | Out-Null
		Invoke-WebRequest "https://meta.fabricmc.net/v2/versions/loader/$minecraft/$loader/profile/json" -OutFile $json -UseBasicParsing
	}
	return $id
}

# The "Ruby Wars" entry in the launcher's list (in each profile file the launcher keeps).
function Set-LauncherProfile([string]$versionId) {
	$memory = 3
	try {
		$gb = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB
		if ($gb -ge 15) { $memory = 6 } elseif ($gb -ge 11) { $memory = 4 }
	} catch {
	}
	$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
	$entry = [pscustomobject]@{
		name = 'Ruby Wars'
		type = 'custom'
		created = $now
		lastUsed = $now
		icon = 'Redstone_Block'
		lastVersionId = $versionId
		gameDir = $GameDir
		javaArgs = "-Xmx${memory}G -XX:+UseG1GC"
	}
	$files = @('launcher_profiles.json', 'launcher_profiles_microsoft_store.json') | ForEach-Object { Join-Path $McDir $_ } | Where-Object { Test-Path -LiteralPath $_ }
	if (-not $files) {
		$files = @(Join-Path $McDir 'launcher_profiles.json')
		[IO.File]::WriteAllText($files[0], '{"profiles":{},"version":3}', $Utf8)
	}
	foreach ($path in $files) {
		$data = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
		$backup = "$path.before-rubywars"
		if (-not (Test-Path -LiteralPath $backup)) {
			Copy-Item -LiteralPath $path -Destination $backup
		}
		if (-not $data.profiles) {
			$data | Add-Member -NotePropertyName profiles -NotePropertyValue ([pscustomobject]@{}) -Force
		}
		$had = $data.profiles.PSObject.Properties['ruby-wars']
		if ($had) {
			# Keep when it was made, and any memory setting the player changed themselves.
			$entry.created = $had.Value.created
			if ($had.Value.javaArgs) { $entry.javaArgs = $had.Value.javaArgs }
		}
		$data.profiles | Add-Member -NotePropertyName 'ruby-wars' -NotePropertyValue $entry -Force
		[IO.File]::WriteAllText($path, ($data | ConvertTo-Json -Depth 32), $Utf8)
	}
}

# The server in the multiplayer list (only when the pack names one, and the player has no list of their own yet).
function Set-ServerList {
	$address = Join-Path $GameDir 'rubywars-server.txt'
	$servers = Join-Path $GameDir 'servers.dat'
	if (-not (Test-Path -LiteralPath $address) -or (Test-Path -LiteralPath $servers)) {
		return
	}
	$ip = (Get-Content -LiteralPath $address | Select-Object -First 1).Trim()
	if (-not $ip) {
		return
	}
	$out = New-Object IO.MemoryStream
	$text = {
		param([string]$s)
		$bytes = [Text.Encoding]::UTF8.GetBytes($s)
		$out.WriteByte([byte]($bytes.Length -shr 8))
		$out.WriteByte([byte]($bytes.Length -band 255))
		$out.Write($bytes, 0, $bytes.Length)
	}
	# NBT: { servers: [ { name: "Ruby Wars", ip: <address> } ] }
	$out.WriteByte(10); & $text ''
	$out.WriteByte(9); & $text 'servers'; $out.WriteByte(10)
	$out.Write([byte[]](0, 0, 0, 1), 0, 4)
	$out.WriteByte(8); & $text 'name'; & $text 'Ruby Wars'
	$out.WriteByte(8); & $text 'ip'; & $text $ip
	$out.WriteByte(0)
	$out.WriteByte(0)
	[IO.File]::WriteAllBytes($servers, $out.ToArray())
}

# Starting settings, the first time only (never touched again, so whatever the player changes stays changed).
# Their own Minecraft settings if they have some (their keys, their mouse speed); otherwise friendly ones for a new player.
function Set-Options {
	$target = Join-Path $GameDir 'options.txt'
	if (Test-Path -LiteralPath $target) {
		return
	}
	$own = Join-Path $McDir 'options.txt'
	$lines = New-Object System.Collections.Generic.List[string]
	if (Test-Path -LiteralPath $own) {
		foreach ($line in Get-Content -LiteralPath $own) { $lines.Add($line) }
	} else {
		# Bright enough to see in caves, music at half, jump up single blocks by yourself, a view distance most PCs manage.
		foreach ($line in @('gamma:1.0', 'soundCategory_music:0.5', 'autoJump:true', 'renderDistance:10', 'guiScale:0', 'narrator:0')) { $lines.Add($line) }
	}
	# No vanilla tutorial pop-ups, first-run screens or multiplayer warning between a player and the game.
	$always = @{ tutorialStep = 'none'; onboardAccessibility = 'false'; skipMultiplayerWarning = 'true'; joinedFirstServer = 'true' }
	foreach ($key in $always.Keys) {
		$found = $false
		for ($i = 0; $i -lt $lines.Count; $i++) {
			if ($lines[$i].StartsWith("${key}:")) {
				$lines[$i] = "${key}:" + $always[$key]
				$found = $true
			}
		}
		if (-not $found) {
			$lines.Add("${key}:" + $always[$key])
		}
	}
	[IO.File]::WriteAllLines($target, $lines, $Utf8)
}

function Find-Launcher {
	$candidates = @(
		(Join-Path ${env:ProgramFiles(x86)} 'Minecraft Launcher\MinecraftLauncher.exe'),
		(Join-Path $env:ProgramFiles 'Minecraft Launcher\MinecraftLauncher.exe'),
		'C:\XboxGames\Minecraft Launcher\Content\Minecraft.exe'
	)
	foreach ($path in $candidates) {
		if ($path -and (Test-Path -LiteralPath $path)) {
			return $path
		}
	}
	try {
		if (Get-AppxPackage -Name 'Microsoft.4297127D64EC6') {
			return 'shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft'
		}
	} catch {
	}
	return $null
}

function Test-LauncherRunning {
	return [bool](Get-Process -Name 'MinecraftLauncher', 'Minecraft' -ErrorAction SilentlyContinue)
}

# Keep a copy of this file in the game folder, and point a desktop shortcut at it.
function Install-Shortcut([string]$launcher) {
	$installed = Join-Path $GameDir 'Ruby Wars.cmd'
	if ($Self -and ((Resolve-Path -LiteralPath $Self).Path -ne $installed)) {
		Copy-Item -LiteralPath $Self -Destination $installed -Force
	}
	if ($env:RW_NOSHORTCUT) {
		return
	}
	$shell = New-Object -ComObject WScript.Shell
	$link = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'Ruby Wars.lnk'))
	$link.TargetPath = Join-Path $env:SystemRoot 'System32\cmd.exe'
	$link.Arguments = "/c `"`"$installed`" play`""
	$link.WorkingDirectory = $GameDir
	$link.WindowStyle = 7
	$link.Description = 'Play Ruby Wars (updates itself first)'
	if ($launcher -and $launcher -notlike 'shell:*') {
		$link.IconLocation = "$launcher,0"
	}
	$link.Save()
}

# Bring the game folder up to date. Returns $true when something changed.
function Update-RubyWars {
	$tag = Get-LatestTag
	$versionFile = Join-Path $GameDir 'rubywars-version.txt'
	$have = if (Test-Path -LiteralPath $versionFile) { (Get-Content -Raw -LiteralPath $versionFile).Trim() } else { '' }
	if (-not $tag) {
		if ($have) {
			Say "  Couldn't reach GitHub: keeping the version you have." 'Yellow'
			return $false
		}
		throw "Couldn't reach GitHub. Check the internet connection and try again."
	}
	if ($tag -eq $have) {
		Say '  Ruby Wars is up to date.' 'Green'
		return $false
	}
	$versions = Sync-Pack $tag
	$id = Install-Fabric $versions.Minecraft $versions.Loader
	Set-LauncherProfile $id
	Set-ServerList
	Say "  Updated to $tag." 'Green'
	return $true
}

try {
	Say ''
	Say '  RUBY WARS' 'Red'
	Say ''
	if (-not (Test-Path -LiteralPath $McDir)) {
		Say '  Minecraft has never been started on this PC.' 'Yellow'
		Say '  Install the Minecraft Launcher (it is free in the Microsoft Store), sign in, press Play once, close it,'
		Say '  then run this again.'
		if (-not $Quiet) {
			Start-Process 'https://www.minecraft.net/download'
		}
		Pause-End 'Press Enter to close'
		return
	}
	$launcher = Find-Launcher
	if ($Mode -eq 'play') {
		Say '  Checking for updates...'
		try {
			if ((Test-LauncherRunning) -and ((Get-LatestTag) -ne ((Get-Content -Raw -LiteralPath (Join-Path $GameDir 'rubywars-version.txt') -ErrorAction SilentlyContinue) + '').Trim())) {
				Say '  There is an update, but the Minecraft Launcher is open. Close it and click Ruby Wars again to get it.' 'Yellow'
				Start-Sleep -Seconds 4
			} else {
				Update-RubyWars | Out-Null
			}
		} catch {
			Say ("  Couldn't update just now: " + $_.Exception.Message) 'Yellow'
			Start-Sleep -Seconds 4
		}
	} else {
		if ((Test-LauncherRunning) -and -not $Quiet) {
			Say '  The Minecraft Launcher is open. Close it first (so it picks up Ruby Wars), then come back here.' 'Yellow'
			Pause-End 'Press Enter once it is closed'
		}
		Say '  Installing. This takes a minute the first time.'
		$changed = Update-RubyWars
		if (-not $changed) {
			# Already up to date: make sure the launcher entry and Fabric are still there all the same.
			$index = $null
			$versions = Sync-Pack (Get-LatestTag)
			Set-LauncherProfile (Install-Fabric $versions.Minecraft $versions.Loader)
			Set-ServerList
		}
		Set-Options
		Install-Shortcut $launcher
		Say ''
		Say '  Done! From now on, use the "Ruby Wars" shortcut on your desktop.' 'Green'
		Say '  In the launcher, pick "Ruby Wars" (bottom left, next to the Play button) and press Play.'
		Say '  The shortcut checks for a new version every time, so you never have to install again.'
	}
	if (-not $Quiet) {
		if ($launcher) {
			Start-Process $launcher
		} else {
			Say '  I could not find the Minecraft Launcher to open it: open it yourself and pick "Ruby Wars".' 'Yellow'
		}
		if ($Mode -ne 'play') {
			Pause-End 'Press Enter to close'
		}
	}
} catch {
	Say ''
	Say ('  Something went wrong: ' + $_.Exception.Message) 'Red'
	Say '  Nothing was broken. Try again, and if it keeps happening send a photo of this window to whoever runs your server.'
	Pause-End 'Press Enter to close'
	exit 1
}
