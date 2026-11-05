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
    name = "help"
    description = "How to link your account, submit games, and view rankings"
  },
  @{ name = "signup"; description = "Create or connect your account and join this channel's leaderboard" },
  @{ name = "my-id"; description = "Show your shareable profile UUID privately" },
  @{
    name = "add-member"
    description = "Add a profile to this channel's leaderboard"
    options = @(@{ type = 3; name = "uuid"; description = "Shareable profile UUID"; required = $true; min_length = 36; max_length = 36 })
  },
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
    options = @(
      @{
        type = 3
        name = "game"
        description = "Game whose result you are submitting"
        required = $true
        min_length = 1
        max_length = 80
        autocomplete = $true
      },
      @{ type = 3; name = "result"; description = "Complete game share text"; required = $true; min_length = 1; max_length = 6000 }
    )
  },
  @{
    name = "leaderboard"
    description = "Show this channel's leaderboard rankings"
    options = @(
      @{
        type = 3
        name = "game"
        description = "Game to rank"
        required = $true
        min_length = 1
        max_length = 80
        autocomplete = $true
      },
      @{
        type = 3
        name = "range"
        description = "Period to rank (defaults to today)"
        required = $false
        choices = @(
          @{ name = "Today"; value = "today" },
          @{ name = "Last 7 days"; value = "7d" },
          @{ name = "Last 30 days"; value = "30d" },
          @{ name = "All time"; value = "all" }
        )
      }
    )
  }
)

if ($Scope -eq "Global") {
  foreach ($command in $commands) {
    $command.contexts = @(0)
    $command.integration_types = @(0)
  }
}

Write-Warning "Discord bulk registration replaces every command owned by this application in the selected $Scope scope."
$headers = @{ Authorization = "Bot $botToken"; "User-Agent" = "DiscordBot (https://dailytracker.cc, 0.1.0)" }
$body = $commands | ConvertTo-Json -Depth 8 -Compress
$registered = Invoke-RestMethod -Method Put -Uri $endpoint -Headers $headers -ContentType "application/json" -Body $body
Write-Host "Registered $($registered.Count) Discord commands in $Scope scope."
