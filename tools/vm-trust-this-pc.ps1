# One-time: let this PC log in to the Ruby Wars VM with an SSH key (no password), let the VM user run the
# rubywars commands without a sudo password, and update the server.
# Asks for the VM password, then the sudo password, once.
#   & .\tools\vm-trust-this-pc.ps1
param(
	[string]$Server = "gregrose0929@192.168.0.115",
	[string]$VmUser = "gregrose0929"
)
$keyFile = "$env:USERPROFILE\.ssh\id_ed25519.pub"
if (-not (Test-Path $keyFile)) {
	Write-Error "No SSH key at $keyFile. Create one with: ssh-keygen -t ed25519"
	exit 1
}
$key = (Get-Content $keyFile -Raw).Trim()
# (No double quotes in the remote command: Windows PowerShell 5.1 drops them on the way to ssh.)
$remote = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && (grep -qF '$key' ~/.ssh/authorized_keys || echo '$key' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys && echo KEY INSTALLED && curl -fsSL https://raw.githubusercontent.com/gregrose0929/ruby-wars/main/tools/setup-server.sh -o setup-server.sh && sudo ALLOW_USER=$VmUser MEMORY=4G bash setup-server.sh"
ssh -t $Server $remote
