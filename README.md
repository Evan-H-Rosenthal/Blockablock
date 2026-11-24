# Blockablock 1.0.0

Blockablock gives players unlockable palettes of infinite building blocks on Minecraft Java Edition 26.3, Paper build 259, and Java 25. Bedrock players can use the same rewards and commands when they connect through Geyser. [Paper downloads](https://papermc.io/downloads/paper) · [Geyser supported versions](https://geysermc.org/wiki/geyser/supported-versions/)

## Build and install

1. Use Java 25. Run `./gradlew build` on Linux or macOS, or `gradlew.bat build` on Windows.
2. Place `build/libs/Blockablock-1.0.0.jar` in the server's `plugins` folder and restart Paper.
3. Grant a test player a block with `/blockablock grant <player> minecraft:stone`.

Blockablock creates `plugins/Blockablock/config.yml` on first launch. It saves the on/off switch and all rules by the UUID Paper assigns, including offline players' rules.

## Commands

| Command | Effect |
| --- | --- |
| `/blockablock grant <player selector> <block id>` | Gives one infinite block reward. Grant takes a single block ID. |
| `/blockablock revoke <player selector> <block id or *>` | Removes the selected infinite reward or all of that player's infinite rewards. |
| `/blockablock list <player selector>` | Sends the selected players' saved grants and blacklists privately to the command sender. |
| `/blockablock blacklist <player selector> <block id or *>` | Removes any matching reward and prevents possession and use of the selected block or all block items. |
| `/blockablock toggle` | Turns rewards and blacklist enforcement on or off without resetting player rules. |
| `/blockablock reset` | Prompts for confirmation before clearing every player's grants and blacklists, including offline players. Type `yes` in chat within 30 seconds or run `/blockablock reset yes` from the same sender. |

Anyone can use `list`. Changes require an operator, the server console, or a command block. Other plugins can dispatch these commands through Bukkit using an appropriately authorized sender. Targets can be vanilla selectors such as `@a`, `@p` and `@s`, a known player name, or a UUID. Selectors match online players. In a command block, enter the command without the leading slash. Confirm a command-block reset from the same block.

Use Minecraft block items such as `oak_planks` or `minecraft:oak_planks`. The most recent `grant` or `blacklist` for a block determines its rule. After `blacklist *`, a specific grant makes that block an exception to the wildcard. Revoking that grant makes the wildcard apply to it again. Reset keeps the current on/off setting.

## Gameplay

- Rules apply to non-operator players in Survival. Players can use ordinary blocks unless the blocks are blacklisted.
- Each grant gives one `Infinite <Block Name>` item that can move through the player's inventory and be placed from either hand. The copy returns to the placement slot on the next server tick. If the inventory is full, an unissued reward waits for a free slot.
- Attempted pickups of granted or blacklisted blocks remove the world item. Blacklisting also clears matching blocks already in the player's inventory. Existing ordinary stacks of a granted block stay in the inventory.
- Infinite items stay with the player through placement, drop attempts, death, and reconnects. They cannot be stored in containers, used as crafting ingredients, or passed to other players.
- Breaking blocks uses vanilla drops. Switching Blockablock off removes its reward items and restores ordinary pickup and placement. Turning it back on restores saved rewards for eligible players.
