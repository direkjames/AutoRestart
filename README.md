# AutoRestart

Private Paper plugin that restarts the server on a schedule, with countdown warnings, staff overrides,
PlaceholderAPI placeholders and Discord webhook announcements.

- **Server:** Paper 26.3 (also loads on 26.1 / 26.2)
- **Java:** 25
- **Optional:** PlaceholderAPI

## Building

Open the folder in IntelliJ IDEA and let Gradle sync. If you don't have a Java 25 JDK, Gradle downloads one
automatically. Then run the `build` task (Gradle tool window → Tasks → build → build), or:

```
./gradlew build
```

The jar is written to `build/libs/AutoRestart-<version>.jar`. `build` also runs the unit tests for schedules
and time parsing.

## Pterodactyl setup

A plugin can only stop the server; something else has to start it again. On Pterodactyl, Wings does that:
by default it treats a clean stop that wasn't triggered from the panel as a crash and starts the server back up.

The console will say **"Detected server process in a crashed state!" with exit code 0**. That's expected and
safe: the plugin does a normal Paper shutdown (the same as typing `stop`), which saves all worlds and player
data first. A real crash shows a Java stack trace or a non-zero exit code.

Two things to keep in mind:

1. Leave crash detection on for the server (it's on by default).
2. Wings won't auto-restart if the previous "crash" was less than 60 seconds ago. That's why `min-uptime`
   defaults to `10m`. Don't set it below `2m`.

Test it once: run `/autorestart now 10s test` and watch the console come back up in the panel.

## Schedules

```yaml
timezone: "Asia/Manila"
schedules:
  - "Daily;06;00"
  - "Monday;23;00"
interval: "6h"     # optional: also restart every 6 hours of uptime
min-uptime: "10m"
```

Format is `Day;HH;MM` in 24-hour time. `Day` is `Monday`–`Sunday` or `Daily`. The earliest upcoming schedule
(or interval) wins.

## Commands

Main command `/autorestart`, alias `/ar`.

| Command | What it does | Permission |
|---|---|---|
| `/ar` or `/ar time` | Show when the next restart is | everyone |
| `/ar now <time> [reason]` | Restart `<time>` from now, replacing the plan | `autorestart.admin.now` |
| `/ar delay <time> [reason]` | Push the pending restart back by `<time>` | `autorestart.admin.delay` |
| `/ar cancel [reason]` | Skip the pending restart; the next scheduled one still happens | `autorestart.admin.cancel` |
| `/ar reload` | Reload `config.yml` | `autorestart.admin.reload` |

`<time>` accepts `300` (seconds), `5m`, `1h30m`, `2h 15m` or `1:30` (hours:minutes).
`autorestart.admin` gives all of the above. Staff with `autorestart.notify` are told when someone changes the restart.

A manual restart (`now`/`delay`) survives `/ar reload`. After the server comes back up, the normal schedule applies again.

## Warnings

Each key under `warnings:` is a point in the countdown (`30m`, `1m`, `10s`, …). Every field is optional:

```yaml
warnings:
  "1m":
    chat: "<prefix><red>Restarting in <time>!"
    actionbar: "<red>Restarting in <time>"
    title: "<red>Restarting soon"
    subtitle: "<gray>in <white><time>"
    sound: "block.note_block.bell"
```

Text uses [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Placeholders: `<time>`, `<reason>`,
`<date>`, `<prefix>` (and `<player>` in command messages). A boss bar also counts down during the last
`bossbar.show-at`.

If the server lags past several warnings at once, only the closest one is shown, so players don't get spammed.

## PlaceholderAPI

| Placeholder | Example |
|---|---|
| `%autorestart_time_left%` | `1h 5m` (`-` if none) |
| `%autorestart_time_left_seconds%` | `3900` (`-1` if none) |
| `%autorestart_next%` | `Thu, Oct 8 at 6:00 PM` |
| `%autorestart_reason%` | the reason, or `messages.no-reason` |
| `%autorestart_type%` | `schedule` or `manual` |

## Discord webhook

In Discord: channel settings → Integrations → Webhooks → New Webhook → Copy Webhook URL. Then:

```yaml
discord:
  enabled: true
  url: "https://discord.com/api/webhooks/..."
  announce-at: ["10m", "1m"]
```

It posts when a restart is coming (`announce-at`), when staff use `now`/`delay`/`cancel`, and when the server
restarts. Messages are under `discord.messages` with `{time}`, `{reason}`, `{player}`, `{date}`.
Mentions such as `@everyone` in a reason are not pinged.

## Project layout

```
src/main/java/dev/autorestart/
  AutoRestartPlugin.java      plugin entry point
  Settings.java               reads and validates config.yml
  RestartManager.java         pending restart, countdown, boss bar, shutdown
  Messenger.java              MiniMessage rendering and broadcasts
  command/                    /autorestart (Brigadier)
  core/                       schedule + time logic (no Paper code, unit tested)
  hook/                       PlaceholderAPI expansion, Discord webhook
```
