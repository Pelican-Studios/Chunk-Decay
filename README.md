# Chunk Decay — by Pelican Studios

A singleplayer minigame mod for **Fabric 1.21.11**. Every chunk you leave crumbles into the void a few seconds later, top to bottom, bedrock included. You can never go back.

## How to play

1. Load any world (cheats don't need to be on).
2. Type `/chunkdecay start`.
3. Move. The chunk you just left starts a countdown, then collapses into the void.

- If you walk back into a chunk that's counting down, the action bar warns you: **"⚠ This chunk decays in 3s - get out!"**, with ticking clicks for the last 3 seconds.
- Blocks you place in a void chunk (bridging) decay too once you leave that chunk.
- Nether portals decay behind you as well, so once you go through, the way back might be gone.
- If your respawn point has decayed, you get a small 3×3 grass "lifeboat" platform so you don't respawn straight into the void.
- Spectator mode is ignored, so you can fly around and look at the damage.
- Everything is saved with the world, including the countdowns, so you can quit and come back.

## Commands

| Command | What it does |
|---|---|
| `/chunkdecay` or `/chunkdecay status` | Show settings and stats |
| `/chunkdecay start` | Start the game (your current chunk is safe until you leave it) |
| `/chunkdecay stop` | Stop. Chunks that were queued are spared |
| `/chunkdecay delay <0-600>` | Seconds before a chunk you left collapses (default 5) |
| `/chunkdecay speed <1-64>` | Layers removed per tick while collapsing (default 4; higher is faster) |
| `/chunkdecay particles on/off` | Toggle the crumble particles |
| `/chunkdecay resetstats` | Reset the "chunks decayed" counter |

## Building

```
gradle build
```

The jar ends up in `build/libs/chunkdecay-1.0.1.jar`. Put it in your `mods` folder along with Fabric API.

Versions are pinned in `gradle.properties`: yarn `1.21.11+build.6`, loader `0.18.4`, Fabric API `0.141.6+1.21.11`, Loom `1.17-SNAPSHOT`.
