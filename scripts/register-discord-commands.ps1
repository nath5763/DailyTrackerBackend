[CmdletBinding()]
param(
  [ValidateSet("Guild", "Global")]
  [string]$Scope = "Guild",
  [switch]$ConfirmGlobal
)

$ErrorActionPreference = "Stop"

$applicationId = $env:DISCORD_APPLICATION_ID
$botToken = $env:DISCORD_BOT_TOKEN
$guildId = $env:DISCORD_TEST_GUILD_ID

if ([string]::IsNullOrWhiteSpace($applicationId)) { throw "DISCORD_APPLICATION_ID is required." }
if ([string]::IsNullOrWhiteSpace($botToken)) { throw "DISCORD_BOT_TOKEN is required for registration." }
if ($applicationId -notmatch '^\d{1,20}$') { throw "DISCORD_APPLICATION_ID must be a Discord snowflake." }

if ($Scope -eq "Guild") {
  if ([string]::IsNullOrWhiteSpace($guildId)) { throw "DISCORD_TEST_GUILD_ID is required for Guild registration." }
  if ($guildId -notmatch '^\d{1,20}$') { throw "DISCORD_TEST_GUILD_ID must be a Discord snowflake." }
  $endpoint = "https://discord.com/api/v10/applications/$applicationId/guilds/$guildId/commands"
} else {
  if (-not $ConfirmGlobal) {
    throw "Global registration can take time to propagate. Re-run with -Scope Global -ConfirmGlobal after guild smoke testing."
  }
  $endpoint = "https://discord.com/api/v10/applications/$applicationId/commands"
}

$commands = @(
  @{
    name = "link"
    description = "Link your Discord identity to your Daily Tracker profile"
    options = @(@{ type = 3; name = "code"; description = "One-time code from your Daily Tracker dashboard"; required = $true; min_length = 12; max_length = 14 })
  },
  @{
    name = "unlink"
    description = "Unlink your Discord identity from Daily Tracker"
  },
  @{
    name = "setup"
    description = "Connect this channel to a Daily Tracker leaderboard"
    default_member_permissions = "32"
    options = @(@{ type = 3; name = "leaderboard"; description = "Leaderboard UUID"; required = $true; min_length = 36; max_length = 36 })
  },
  @{
    name = "daily"
    description = "Submit a daily-game share result to this channel's leaderboard"
    options = @(@{ type = 3; name = "result"; description = "Complete Fermi or Krillion share text"; required = $true; min_length = 1; max_length = 6000 })
  }
)

if ($Scope -eq "Global") {
  foreach ($command in $commands) {
    $command.contexts = @(0)
    $command.integration_types = @(0)
  }
}

Write-Warning "Discord bulk registration replaces every command owned by this application in the selected $Scope scope."
$headers = @{ Authorization = "Bot $botToken"; "User-Agent" = "DailyTrackerCommandRegistrar/1.0" }
$body = $commands | ConvertTo-Json -Depth 8 -Compress
$registered = Invoke-RestMethod -Method Put -Uri $endpoint -Headers $headers -ContentType "application/json" -Body $body
Write-Host "Registered $($registered.Count) Discord commands in $Scope scope."
