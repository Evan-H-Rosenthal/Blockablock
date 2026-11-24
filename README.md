# Blockablock 1.0.1

Blockablock gives players unlockable palettes of infinite building blocks on Minecraft Java Edition 26.3, Paper build 259, and Java 25. Bedrock players can use the same rewards and commands when they connect through Geyser. [Paper downloads](https://papermc.io/downloads/paper) · [Geyser supported versions](https://geysermc.org/wiki/geyser/supported-versions/)

## Build and install

1. Use Java 25. Run `./gradlew build` on Linux or macOS, or `gradlew.bat build` on Windows.
2. Place `build/libs/Blockablock-1.0.1.jar` in the server's `plugins` folder, replacing the older Blockablock JAR if present, and restart Paper.
3. Grant a test player a block with `/blockablock grant <player> minecraft:stone`.

Blockablock creates `plugins/Blockablock/config.yml` on first launch and adds missing settings when upgrading. The file saves the on/off switch and player rules by the UUID Paper assigns. Restart Paper after editing settings.

## Configuration

| Setting | Default | Effect |
| --- | --- | --- |
| `blocks-per-grant` | `2` | Size of each grant and the amount infinite rewards refill to. Use a positive integer. |
| `blocks-for-replenish` | `2` | Refill threshold for infinite rewards. Use `0` to refill when the last block is used. |
| `infinite-replenish` | `true` | With `false`, each `grant` issues a fresh, consumable batch. |
| `commands-on-grant` | `[]` | Commands run for each player targeted by `grant`. |
| `commands-on-revoke` | `[]` | Commands run for each player targeted by `revoke`. |
| `commands-on-blacklist` | `[]` | Commands run for each player targeted by `blacklist`. |
| `prepended-text` | `''` | Text before the block name. Include any desired trailing space. |
| `appended-text` | `''` | Text after the block name. Include any desired leading space. |
| `name-color` | `'#FFFFFF'` | A quoted six-digit hex color. Invalid colors fall back to white. |

Hook commands run in order with console permissions and the affected player as `@s`. Relative coordinates use that player's position. Commands can include `execute` and vanilla selectors. Use strings such as `'execute at @s run particle minecraft:flame ~ ~1 ~ 0 0 0 0 1'`. Hooks for offline players wait until they join.

## Commands

| Command | Effect |
| --- | --- |
| `/blockablock help [command]` | Lists help topics or explains one command. |
| `/blockablock grant <player selector> <block id>` | Gives a block reward. Grant takes a single block ID. |
| `/blockablock revoke <player selector> <block id or *>` | Removes a grant or all of that player's grants. |
| `/blockablock list <player selector>` | Sends the selected players' saved grants and blacklists privately to the command sender. |
| `/blockablock blacklist <player selector> <block id or *>` | Removes any matching reward and prevents possession and use of the selected block or all block items. |
| `/blockablock toggle` | Turns rewards and blacklist enforcement on or off without resetting player rules. |
| `/blockablock reset` | Prompts for confirmation before clearing every player's grants and blacklists, including offline players. Type `yes` in chat within 30 seconds or run `/blockablock reset yes` from the same sender. |

Anyone can use `help` and `list`. Changes require an operator, the server console, or a command block. Other plugins can dispatch these commands through Bukkit using an appropriately authorized sender. Targets can be vanilla selectors such as `@a`, `@p` and `@s`, a known player name, or a UUID. Selectors match online players. In a command block, enter the command without the leading slash. Confirm a command-block reset from the same block.

Use Minecraft block items such as `oak_planks` or `minecraft:oak_planks`. The most recent `grant` or `blacklist` for a block determines its rule. After `blacklist *`, a specific grant makes that block an exception to the wildcard. Revoking that grant makes the wildcard apply to it again. Reset keeps the current on/off setting.

## Gameplay

- Rules apply to non-operator players in Survival. Players can use ordinary blocks unless the blocks are blacklisted.
- Infinite rewards can move through the player's inventory and be placed from either hand. They refill on the next server tick once stock reaches the threshold, using the placement slot first.
- Finite rewards are ordinary consumable items once delivered. Grant again for another batch. Undelivered items wait for an eligible player with inventory space.
- Attempted pickups of infinitely granted or blacklisted blocks remove the world item. Blacklisting also clears matching blocks already in the player's inventory. Existing ordinary stacks of an infinitely granted block stay in the inventory.
- Infinite items stay with the player through placement, drop attempts, death, and reconnects. They cannot be stored in containers, used as crafting ingredients, or passed to other players.
- Breaking blocks uses vanilla drops. Switching Blockablock off removes its infinite reward items and restores ordinary pickup and placement. Turning it back on restores infinite rewards for eligible players.
