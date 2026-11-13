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
  @{ name = "signup"; description = "Create or connect your Daily Tracker account" },
  @{ name = "my-id"; description = "Show your shareable profile UUID privately" },
  @{
    name = "add-member"
    description = "Add a profile to a leaderboard"
    options = @(
      @{ type = 3; name = "uuid"; description = "Shareable profile UUID"; required = $true; min_length = 36; max_length = 36 },
      @{ type = 3; name = "leaderboard"; description = "Leaderboard UUID (required in DMs or unconfigured Group DMs)"; required = $false; min_length = 36; max_length = 36 }
    )
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
    description = "Submit a daily-game share result to a leaderboard"
    options = @(
      @{ type = 3; name = "result"; description = "Complete game share text"; required = $true; min_length = 1; max_length = 6000 },
      @{ type = 3; name = "leaderboard"; description = "Leaderboard UUID (required in DMs or unconfigured Group DMs)"; required = $false; min_length = 36; max_length = 36 }
    )
  },
  @{
    name = "leaderboard"
    description = "Show today's card or a game's rankings"
    options = @(
      @{
        type = 3
        name = "game"
        description = "Game to rank"
        required = $false
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
      },
      @{ type = 3; name = "leaderboard"; description = "Leaderboard UUID (required in DMs or unconfigured Group DMs)"; required = $false; min_length = 36; max_length = 36 }
    )
  }
)

if ($Scope -eq "Global") {
  foreach ($command in $commands) {
    $command.contexts = @(0, 1, 2)
    $command.integration_types = @(0, 1)
  }
  $commands += @(
    @{
      name = "set-leaderboard"
      description = "Connect this Group DM to a Daily Tracker leaderboard"
      contexts = @(2)
      integration_types = @(1)
      options = @(@{ type = 3; name = "leaderboard"; description = "Leaderboard UUID"; required = $true; min_length = 36; max_length = 36 })
    },
    @{
      name = "unset-leaderboard"
      description = "Disconnect this Group DM from its leaderboard"
      contexts = @(2)
      integration_types = @(1)
    }
  )
}

$headers = @{ Authorization = "Bot $botToken"; "User-Agent" = "DiscordBot (https://dailytracker.cc, 0.1.0)" }
$registered = 0
foreach ($command in $commands) {
  $body = $command | ConvertTo-Json -Depth 8 -Compress
  Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -ContentType "application/json" -Body $body | Out-Null
  $registered++
}
Write-Host "Upserted $registered Discord commands in $Scope scope without deleting unrelated commands."
