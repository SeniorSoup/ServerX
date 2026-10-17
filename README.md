# ServerX (Fabric, Minecraft 26.2, Java 25)

Moderation utility mod with LuckPerms integration (via fabric-permissions-api, which LuckPerms hooks into).

## Build
Needs JDK 25 and Gradle 9.5+.
    gradle wrapper --gradle-version 9.5.1
    ./gradlew build
Jar: build/libs/serverx-1.0.0.jar  (permissions API is bundled via `include`).
Server needs: Fabric Loader 0.19.x, Fabric API, LuckPerms (Fabric).

## Permission nodes (grant with LuckPerms, console always allowed)
| Node | Use |
|---|---|
| serverx.cmd | /cmd |
| serverx.protect | /protect (all subcommands) |
| serverx.setprison | /setprison |
| serverx.punishban | /punishban |
| serverx.pardonban | /pardonban |
| serverx.prison | /prison |
| serverx.ipban | /ipban |
| serverx.ipunban | /ipunban |
| serverx.ipbanlist | /ipbanlist |
| serverx.bypass | ignore region rules |

First-time setup from the server console:
    lp user <you> permission set serverx.* true
(or `lp group admin permission set serverx.* true`)

## Commands
- /cmd <command> : runs as console, output returned to you
- /protect make <name> | edit <name> bounds | edit <name> decay <sec> | del <name> | list | help
- /setprison, /punishban <player>, /pardonban <player>, /prison <player> <time>   (time: 30s 10m 2h 1d 1w 1h30m)
- /ipban <player|ip> [reason] : bans the IP, kicks everyone on it, and disconnects any account that joins from it later (their names are recorded as alts)
- /ipunban <ip|name>, /ipbanlist

Data is stored in config/serverx.json.
