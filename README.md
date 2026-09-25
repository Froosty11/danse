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
- **`/danse source`** (open to every player) and `/gesture source` print where this source lives, as
  the AGPL asks of a network server. `fabric.mod.json` says `AGPL-3.0-only` (upstream's said LGPL,
  while its LICENSE file is the AGPL) and points at this fork.
