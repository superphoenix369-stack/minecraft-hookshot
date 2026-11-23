# Hookshot (Fabric, Minecraft 26.3)

A Zelda-style hookshot for Minecraft. Fire a chain, and depending on the mode it either
**drags things to you** or **zips you to wherever it bites**.

## How it plays

| Action | What happens |
|---|---|
| **Right-click** | Fire the hook (range 24 blocks). |
| **Right-click again** while it's out | Reel it back in early (also cancels a zip). |
| **Sneak + right-click** | Switch between **Fetch** mode (red ring) and **Travel** mode (teal ring). |
| **Sneak** while zipping | Let go mid-flight. |

**Fetch mode** (the default)
- Hits **dropped items / XP orbs**: drags them back to you. Hitting the ground near items also grabs them.
- Hits a **small mob** (about player-sized or smaller): deals 1 heart and drags it to your feet.
  Big mobs (iron golems, wardens, bosses) and other players aren't dragged.
- Hits a **loose block**: yanks it out and brings you its drop. A block counts as "movable" if
  a piston could push it, it's soft (hardness ≤ 2: dirt, sand, gravel, wood, leaves, wool…),
  it's not a container or other block entity, and it doesn't need a pickaxe-type tool to drop
  anything. Each block grab costs 1 durability.
- Hits anything else: *clink*, and the hook comes back.

**Travel mode**
- Hits a block or a mob: pulls **you** to it at speed. No fall damage while you're being pulled, plus a
  little hop when you arrive so you can latch onto the lip of a wall and climb over.

Recipe (shaped, diagonal):
```
    . . T      T = Tripwire Hook
    . C .      C = Iron Chain
    I . .      I = Iron Ingot
```
It also appears in the Tools & Utilities creative tab. Durability: 250.

## Getting the .jar (no tools to install)

The project includes a GitHub Actions workflow that compiles the mod for you.

1. Create a new, empty repository on GitHub (it can be private).
2. Upload **everything in this folder**, including the hidden `.github` folder and the `gradle`
   folder. (Easiest: on the repo page choose **uploading an existing file** and drag the folder contents
   in. If your file browser hides `.github`, turn on "show hidden files" first.)
3. Open the repo's **Actions** tab. A **build** run starts automatically on every upload
   (or click **build → Run workflow**). It takes about 3–5 minutes.
4. When it shows a green check, open the run and download **hookshot-mod-jar** from the
   *Artifacts* section. Inside the zip, `hookshot-1.0.0+26.3.jar` is the mod. (Ignore the `-sources.jar`.)

If the run fails (red X), open it, click the failed **build** step, copy the error lines, and send
them back to me to fix.

### Building on your own computer instead
Install a Java 25 JDK, then in this folder run `./gradlew build` (macOS/Linux) or `gradlew.bat build`
(Windows). The jar appears in `build/libs/`. `./gradlew runClient` launches a test game with the mod.

## Playing it
Install **Fabric Loader** (0.20.1+) for Minecraft **26.3**, then put the hookshot jar and
**Fabric API** in your `mods` folder. Works in the Modrinth App, Prism, or the vanilla launcher.
It must be installed on both the server and the clients for multiplayer.

## Publishing on Modrinth
1. Sign in at modrinth.com → **Create a project** → type **Mod**.
2. Upload `src/main/resources/assets/hookshot/icon.png` as the icon. Write a description (the
   "How it plays" section above works), and add some screenshots of it in action.
3. **Versions → Create version**: upload the jar, set **Loader: Fabric**, **Game version: 26.3**,
   and add **Fabric API** as a **required** dependency.
4. Pick a license (this project ships with MIT; change `LICENSE` if you prefer another), then submit for review.

Before publishing, put your name in `"authors"` in `src/main/resources/fabric.mod.json`,
and optionally add `"homepage"`/`"sources"` links under `"contact"`.

## Tweaking it
All the gameplay numbers are constants at the top of
`src/main/java/com/hookshot/HookshotEntity.java`: range, speeds, max block hardness, damage,
max mob size. Change them and rebuild.

Blocks that should never be grabbable live in the block tag
`data/hookshot/tags/block/ungrabbable.json` (glass and ice by default). Servers can extend it with a data pack.

Textures are 16×16 PNGs in `src/main/resources/assets/hookshot/textures/item/`. Replace them freely.

## Project layout
```
src/main/java/com/hookshot/
  HookshotMod.java       registers the item, hook entity, mode component, creative tab entry
  HookshotItem.java      right-click / sneak-toggle behaviour
  HookshotEntity.java    the flying hook: hit detection, fetching, block grabbing, zipping
src/client/java/com/hookshot/client/
  HookshotClient.java    client setup
  HookshotRenderer.java  draws the hook head + chain back to your hand
src/main/resources/      fabric.mod.json, textures, models, lang, recipe, block tag
```
