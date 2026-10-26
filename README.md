# danse (dance)
> player gestures/emotes for fabric servers using 1.21.4+ item models

There are 9 gestures at the moment;
- bits
- fall
- grow
- helicopter
- handstand
- wave
- zombie
- dab
- facepalm

More are in the works...!

Using vanilla item models!\
Compatible with Sodium, Iris, and everything else!

Clients don't have to install any mods, they can connect with a vanilla client!


# Commands:
```
/gesture bits
/gesture fall
/gesture grow
/gesture helicopter
/gesture handstand
/gesture wave
/gesture zombie
/gesture dab
/gesture facepalm
```

# Models

Persistent player models can be spawned like this:
```
/summon danse:player_model ~ ~ ~ {Player:Steve,Animation:wave}
```

Animation can be player by modifying the "Animation" NBT string:
```
/data modify entity @e[...] Animation set value wave
```

Changing the skin:
```
/data modify entity @e[...] Player set value Alex
```

---

[Checkout the discord](https://discord.gg/9X6w2kfy89) for more info

---

# How?

The mod uses the new 1.21.4 item models and custom_model_data to dynamically display the skins.

---

# Adding custom animations

Put your .bbmodel or .ajblueprint files into the `config/danse/` folder.
You get can templates [here](https://github.com/tomalbrc/danse/tree/main/src/main/resources/model/danse)

The name of the animation will be used in-game

# License

Versions before 2.0.0 are LPGL-3.0 licensed.

---

# Metacraft fork

This is [Metacraft](https://github.com/Froosty11/metamods)'s fork of
[tomalbrc/danse](https://github.com/tomalbrc/danse), branch `metacraft`, licensed AGPL-3.0 like
upstream. Changes from `v2.6.0+26.3`:

- **Mixins common.** The five mixins are listed under `"mixins"` instead of `"server"`, so a
  gesture can start when the server runs inside a client JVM (Fabric's client game tests). A
  dedicated server behaves the same either way.
- **Body layers** (`de.tomalbrc.danse.api`). Other mods can put an ordinary textured item model on
  each body part of the stand-in (`BodyLayers.register`), drawn just inside Danse's armour, at the
  texture's own resolution; `BodyLayerModels.shell` writes a model that fits a part exactly.
  Ovvar uses it so overalls and their patches are not squashed into Danse's one-pixel-per-texel grid.
- **Gestures end with a real teleport.** Upstream's end-of-gesture position packet carried the
  player's entity id as its teleport id; when that matched the server's pending teleport (the first
  player after a restart), 26.3 kicked the player with "Invalid move player packet received".
- **Blockbench 5 gestures, natively.** Animated Java 1.11 / Blockbench 5 blueprints load as saved
  (`PlayerModel5Importer`), and their keyframes are sampled the way Blockbench plays them
  (`BlockbenchSampler`: the interpolation follows both neighbouring keyframes, Animated Java's
  easing only on linear segments), so a gesture moves in game as it does in the editor. Older 1.7.3
  files still load as before. A blueprint that fails to load logs an error instead of stopping the server.
- **More gestures** (`emotes.ajblueprint`, by Millia in Animated Java 1.11, as saved but for
  `rote pferd` renamed `rote_pferd`): amogus, clap, defeat, floss, fortnite, hopak and rote_pferd,
  rigged on Danse's player rig (body 14, arms ±6, legs 13), where the stand-in's parts are placed.
  These and upstream's gestures are the only built-in ones.
- **Adding a gesture.** Animate in Animated Java 1.11 on Danse's rig: groups named `head`, `body`,
  `arm_r`, `arm_l`, `leg_r`, `leg_l` with their pivots at the rig's (`emotes.ajblueprint` is a
  template; parent groups such as its `construct` may move the whole figure). Each animation is a
  gesture, named after it. Drop the `.ajblueprint` into `config/danse/` on the server, or into
  `src/main/resources/model/danse/` and its name into `PlayerModelRegistry.loadBuiltin()`. The
  dialog's icon is `model/danse/<gesture>_icon.png` in the jar; a song is `<gesture>.nbs` beside the blueprint. To check one against Blockbench,
  `tools/bbpose` draws Blockbench's pose of any frame, and the fork-only `gesture-frames` workflow
  photographs gestures frame by frame in game (`GestureFramesClientTest`); `tools/bbpose/compare.py`
  puts the two side by side.
- **Gesture songs.** A gesture can play a note block song while it runs: an Open Note Block Studio
  file named after it, `model/danse/<gesture>.nbs` in the jar or `config/danse/<gesture>.nbs` on the
  server. It is played with vanilla note block sounds from where the player dances (heard like a
  note block, under the Jukebox/Note Blocks volume) and stops with the gesture. `fortnite` plays
  the Default Dance riff, written down by ear from grande1899's note block doorbell video.
- **`/danse source`** (open to every player) and `/gesture source` print where this source lives, as
  the AGPL asks of a network server. `fabric.mod.json` says `AGPL-3.0-only` (upstream's said LGPL,
  while its LICENSE file is the AGPL) and points at this fork.
