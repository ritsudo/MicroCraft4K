# Minecraft4k (JS port) — Code Description

A description of the JavaScript half of this project (`4k.js`, driven by `4k.html`).

## 1. What it is

`4k.js` is a self-contained software voxel renderer: a JavaScript port of Notch's
famous **"Minecraft 4k"** (the 4096-byte Java applet). It draws a 64×64×64 block
world using a per-pixel **ray caster** on a plain 2D `<canvas>` — no WebGL, no
libraries, no external assets. Everything (every texture, the whole terrain, the
player, the camera) is generated procedurally at load time and re-rendered 50 times
a second directly into an `ImageData` buffer.

The project is a "restore", so the code intentionally mirrors the original quirks
(short variable names, packed integers, global variables, `setInterval` game loop).

## 2. Files

| File | Role |
|------|------|
| `4k.html` | Host page. Defines a `<canvas id="game" width="424" height="240">`, scales it via CSS to **848×480** with nearest-neighbour filtering (chunky pixels), hides the cursor, and loads `4k.js`. |
| `4k.js` | All engine logic (this document). |

## 3. Global state

```js
var ctx;                 // 2D canvas context
var pixels;              // ImageData used as the framebuffer
var w = 212 * 2;         // 424  internal render width
var h = 120 * 2;         // 240  internal render height
var ox, oy, oz;          // camera/player position (world units, 1 unit = 1 block)
var xRot, yRot;          // camera angles (radians)
var yCos, ySin, xCos, xSin; // sin/cos of the angles, refreshed every frame
var map    = new Array(64*64*64);   // the voxel world (block id per cell)
var texmap = new Array(16*16*3*16); // the procedural texture atlas
```

The world is stored as a flat array indexed by a **bit-packed coordinate**:

```
index = z << 12 | y << 6 | x      // == z*4096 + y*64 + x,  x/y/z in 0..63
```

## 4. `init()` — one-time setup

Runs once at the bottom of the file (`init();`). It does five things:

### 4.1 Generate the texture atlas (`texmap`)
A single "atlas" holds **16 block textures** (slots 0–15). Each slot is **16 wide ×
48 tall** (three stacked 16×16 tiles), stored as packed `0xRRGGBB` integers.

Atlas index:
```
texmap[x + y*16 + tex*256*3]      // 256*3 = 768 = one 16×48 slot
```

The three vertical tiles are used for the three visible faces of a block:
* rows `0..15`  → **top** face
* rows `16..31` → **side** faces
* rows `32..47` → **bottom** face

Generation loops `i = 1..15`, `y = 0..47`, `x = 0..15` and paints block types:

| id | colour(s) | what it looks like |
|----|-----------|--------------------|
| 1 | `0x966C4A` + top layer `0x6AAA40` | grass (dirt sides, green top via a pseudo-random `(x*x*3 + x*81)>>2 & 3` ridge) |
| 4 | `0x7F7F7F` | stone (noise re-rolled only 1/3 of the time, so it looks smoother) |
| 5 | `0xB53A15` + `0xBCAFA5` | brick (mortar lines every 4 rows / 8 columns) |
| 7 | `0x675231` + `0xBC9862` | wood log (concentric ring pattern via Chebyshev distance from centre) |
| 8 | `0x50D937`, randomly `0` | leaves (green with transparent `0` holes) |
| 9 | `0x4040ff` | water / blue block |
| others | `0x966C4A` | generic dirt-brown |

Per-pixel **brightness noise** `br = 255 - random()*96` is re-rolled almost every
texel to give everything a grainy, hand-drawn feel. For the bottom tile
(`y >= 32`) the brightness is halved (`brr /= 2`) so undersides read as shaded.
The final packed colour multiplies each channel by `brr/255`.

### 4.2 Canvas / input wiring
```js
canvas = document.getElementById('game');
ctx = canvas.getContext('2d');
canvas.addEventListener('keydown', handleInput, false);
document.addEventListener("mousemove", mouseMoveHandler, false);
```
(`canvas` is assigned without `var`, so it becomes an implicit global — a quirk kept
from the original.)

### 4.3 Generate the world (`map`)
A triple loop over the 64³ grid. For each cell:

```js
var i  = z << 12 | y << 6 | x;
var yd = (y - 32.5) * 0.4;      // distance from the mid-plane in Y
var zd = (z - 32.5) * 0.4;      // distance from the mid-plane in Z
map[i] = (Math.random() * 16) | 0;                                   // random block
if (Math.random() > Math.sqrt(Math.sqrt(yd*yd + zd*zd)) - 0.8)       // 4th root of dist²
    map[i] = 0;                                                      // carve to air
```

The threshold is the **fourth root** of the squared distance from the Y/Z centre
line. Near the centre it is negative, so the test is always true → **air**; far from
the centre it grows past 1, so the test is rarely true → **solid**. The result is a
hollow, cave-like chamber: empty in the middle, solid rock/grass toward the rim —
hence the page title "ChamberedTest". Because `Math.random()` is used, **the world
is different on every load**.

Block ids produced here range `0..15` (0 = air); only ids `1..9` have distinct
textures, the rest share the default brown.

### 4.4 Set up the framebuffer
```js
pixels = ctx.createImageData(w, h);
for (var i = 0; i < w*h; i++)
    pixels.data[i*4 + 3] = 255;   // alpha set once to opaque
```

### 4.5 Start the loop and place the player
```js
setInterval(clock, 1000/50);   // 50 fps game loop
ox = oy = oz = 32.5;           // dead centre of the world
xRot = 0.0; yRot = 0.0;
```
The player starts at `(32.5, 32.5, 32.5)` — the centre of the hollow chamber.

## 5. `clock()` — the game loop

Runs every 20 ms. It only:
1. refreshes the angle trig:
   ```js
   yCos = cos(yRot); ySin = sin(yRot);
   xCos = cos(xRot); xSin = sin(xRot);
   ```
2. calls `renderMinecraft()` to fill `pixels`;
3. blits the framebuffer with `ctx.putImageData(pixels, 0, 0)`.

There is **no fixed timestep, no delta time, and no `requestAnimationFrame`** — just
the classic `setInterval`.

## 6. `renderMinecraft()` — the ray caster

This is the heart of the engine. For every screen pixel `(x, y)` it casts one ray
from the camera and finds which block face it hits, then shades it.

### 6.1 Build the ray direction (camera space → world)
```js
var ___xd = (x - w/2) / h;   // -0.5 .. +0.5 horizontally
var __yd  = (y - h/2) / h;   // -0.5 .. +0.5 vertically
var __zd  = 1;               // looking down +Z by default
```
Then two cheap rotations:
```js
// yaw: rotate in the Y-Z plane using yRot
var ___zd = __zd*yCos + __yd*ySin;
var _yd   = __yd*yCos - __zd*ySin;

// pitch/roll: rotate in the X-Z plane using xRot
var _xd = ___xd*xCos + ___zd*xSin;
var _zd = ___zd*xCos - ___xd*xSin;
```
`_xd, _yd, _zd` are the world-space ray directions. (The naming with leading
underscores is inherited from the size-optimised original.)

### 6.2 DDA voxel traversal (3 independent axis walks)
Rather than one combined DDA, the code runs **three separate marches** — one per
axis `d = 0 (X), 1 (Y), 2 (Z)` — and keeps whichever hit is closest:

```js
var closest = 32;                       // max view distance (blocks)
for (var d = 0; d < 3; d++) {
    var dimLength = (d==0)?_xd : (d==1)?_yd : _zd;
    var ll = 1 / Math.abs(dimLength);   // ray length per 1 unit along this axis
    var xd = _xd*ll, yd = _yd*ll, zd = _zd*ll;   // normalised step per crossing

    // distance to the first grid plane on this axis
    var initial = (d==0)? ox-(ox|0) : (d==1)? oy-(oy|0) : oz-(oz|0);
    if (dimLength > 0) initial = 1 - initial;
    var dist = ll * initial;

    // starting voxel sample point
    var xp = ox + xd*initial, yp = oy + yd*initial, zp = oz + zd*initial;
    if (dimLength < 0) { if(d==0) xp--; if(d==1) yp--; if(d==2) zp--; }
```

Then a walk loop that stops early once it is farther than the best hit so far:

```js
    while (dist < closest) {
        var tex = map[(zp & 63)<<12 | (yp & 63)<<6 | (xp & 63)];  // read voxel (wrapped)
        if (tex > 0) {                                            // solid?
            /* compute texel (see 6.3) */
            if (cc > 0) { col = cc; ddist = ...; br = ...; closest = dist; }
        }
        xp += xd; yp += yd; zp += zd;   // step to next voxel on this axis
        dist += ll;
    }
}
```

Key details:
* `& 63` wraps coordinates, so the 64³ world **tiles endlessly** in every direction.
* Because only single-axis crossings are considered, the voxel index is always
  correct for axis-aligned cubes.
* `closest` starts at **32**, so geometry more than 32 blocks away is culled and the
  whole thing fades to black with distance.

### 6.3 Face selection and UVs
* **Side faces** (`d != 1`): `u = ((xp+zp)*16)&15`, `v = ((yp*16)&15) + 16`
  (middle atlas tile).
* **Top / bottom** (`d == 1`): `u = (xp*16)&15`, `v = (zp*16)&15`, and `+32` when
  `yd < 0` (looking up at an underside → bottom tile).

The texel is read with `texmap[u + v*16 + tex*768]`. If the texel is `0`
(the transparent holes in the leaves texture), the block is **skipped** — you see
through it, an early form of alpha.

### 6.4 Shading
```js
ddist = 255 - ((dist / 32 * 255) | 0);          // distance falloff (0..255)
br    = 255 * (255 - ((d + 2) % 3) * 50) / 255; // per-face-direction light
```
`br` gives the three axes different brightness (`d=0`→155, `d=1`→255, `d=2`→205),
which is what makes the cubes read as 3D without any real lighting. Final colour:

```js
var r = ((col>>16)&0xff) * br * ddist / (255*255);
var g = ((col>> 8)&0xff) * br * ddist / (255*255);
var b = ( col     &0xff) * br * ddist / (255*255);
pixels.data[(x + y*w)*4 + 0] = r;   // +1 = g, +2 = b (alpha left at 255)
```

So each pixel is a single fuzzy (fogged) texel. That is the entire renderer.

## 7. `handleInput(event)` — keyboard (`keydown` on the canvas)

| Key | code | Action |
|-----|------|--------|
| W | 87 | forward: `ox += 0.2*xSin; oz += 0.2*xCos` |
| S | 83 | backward (negative of W) |
| A | 65 | strafe left: `oz += 0.2*xSin; ox -= 0.2*xCos` |
| D | 68 | strafe right (negative of A) |
| Shift | 16 | up: `oy += 0.5` |
| Space | 32 | down: `oy -= 0.5` |

Movement uses `xSin`/`xCos` (the yaw components) and moves only in the X-Z plane.
There is **no collision detection and no gravity** — the player flies straight
through the terrain. Movement is applied per key event, not scaled by frame time,
so holding a key over a slow frame moves you less.

## 8. `mouseMoveHandler(e)` — free-look camera

```js
windowW = w*2; windowH = h*2;             // 848 × 480 (implicit globals)
if (e.clientX < windowW && e.clientY < windowH) {
    xRot = 3.14 + e.clientX / 135;        // ≈ π + x
    yRot = 1.57 - e.clientY / 152;        // ≈ π/2 − y
}
```
The camera angle is a **direct function of the mouse position** over the canvas:
moving the mouse left/right turns you, up/down looks up/down. There is no pointer
lock or momentum — the view rubber-bands to wherever the cursor is.

## 9. Constants and dimensions at a glance

| Constant | Value | Meaning |
|----------|-------|---------|
| `w`, `h` | 424 × 240 | internal render resolution (≈ 101,760 rays/frame) |
| display | 848 × 480 | CSS upscale, nearest-neighbour (chromatic/chunky look) |
| world | 64 × 64 × 64 | voxel volume, wraps with `& 63` |
| atlas | 16 textures of 16×48 | 3 faces × 16×16 per block id |
| view distance | 32 blocks | `closest` init value |
| FPS | 50 | `setInterval(clock, 1000/50)` |
| block size | 1.0 | world units |
| speed | 0.2 / event (0.5 vertical) | per-key movement |

## 10. Notable quirks and limitations (all inherited from the 4k original)

* **No delta-time / no vsync** — `setInterval` only; frame rate varies with CPU load.
* **Implicit globals** — `canvas`, `windowW`, `windowH` are never declared.
* **Tiny variable names** (`ox`, `_xd`, `___zd`, `ll`) — a leftover of the size-golfed original.
* **Random world every load** — the terrain and all texture noise use `Math.random()`.
  (Overridden by the enhancement pass: see §12, which uses a fixed flat world.)
* **No collisions, no gravity, no block placing/breaking** — it is a viewer only.
  (Overridden by the enhancement pass: see §12 and §15.)
* **Culling at 32 units** — distant geometry simply disappears; the original had no
  sky, only black `(0,0,0)` background. (A blue sky is added in §12.)
* **Whole-screen software rendering** — every pixel is ray-marched in JS each frame,
  which is why the resolution is only 424×240.
* **Zoom-out bug / tilt** — the rotation math pivots around the camera with a fixed
  basis, so extreme angles distort.

## 11. How to run

Open `4k.html` in any browser (it needs to load `4k.js` from the same folder).
Move the mouse to look around; use **W/A/S/D** to fly horizontally and
**Shift / Space** for up/down.

---

# Part 2 — Enhancement pass

The engine above was extended while keeping the original raycaster intact. Every
insertion in `4k.js` is tagged with a `[n]` banner comment so it is easy to diff
against the original. This section documents each one.

## 12. Code insertions

### [1] Extra state (top of the file)
```js
var REACH  = 8;                            // build / highlight distance in blocks
var SLOTS  = [1, 4, 5, 6, 7, 8, 9];      // block ids offered in the hotbar
var sel    = 1;                            // selected hotbar slot index (default: stone)
var thumb  = new Array(16);                // 16×16 preview ImageData per block id
var skyTop    = [55, 115, 205];            // zenith colour
var skyBottom = [175, 212, 240];           // horizon colour
var tx = -1, ty = -1, tz = -1;             // targeted block (wrapped 0..63); -1 = none
var nx = 0, ny = 0, nz = 0;                // outward face normal of the target
```
Holds everything the new features need: how far you can reach, what the hotbar
offers, which slot is selected, the cached sky colours, the current target voxel
and the face normal used to decide where a new block is placed.

### [2] Flat terrain generation (`init()`)
Replaces the old random "chamber" loop with a deterministic flat world:
```js
if (y < 48)       map[i] = 0;   // air / sky
else if (y == 48) map[i] = 1;   // grass surface layer
else if (y < 51)  map[i] = 3;   // dirt
else              map[i] = 4;   // stone
```
Because **+Y reads as "down" on screen** in this renderer (see §13), the solid
ground occupies the high-y half and the player spawns at `oy = 44.5`, four blocks
above the grass. The world is fully reproducible now — no `Math.random()`.

### [3] Blue sky (`renderMinecraft()`)
Where the original wrote black for a ray that hit nothing, the code now paints a
vertical gradient keyed to the ray's pitch `_yd` (up = negative):
```js
var t = (1 - _yd) * 0.5;            // 0 = horizon, 1 = zenith
r = skyBottom[0] + (skyTop[0] - skyBottom[0]) * t;   // and g, b
```

### [4] Target highlight (`renderMinecraft()`)
During the ray march the closest hit's voxel (`hx,hy,hz`) and its local texel
(`hu, hv = v & 15`) are recorded. After shading, a pixel belonging to the targeted
block is brightened, and if it sits on the **texel border** (`hu ∈ {0,15}` or
`hv ∈ {0,15}`) it is drawn white — producing a cheap wireframe box on the block you
are looking at. Only active when `pick()` found a block within `REACH`.

### [5] Block picking and editing
* `pick()` — an Amanatides & Woo voxel DDA from the camera along the **exact
  centre-pixel ray** of the renderer, so the crosshair, the highlight and the edit
  always agree. It returns the hit voxel (`tx/ty/tz`, wrapped) and the outward face
  normal (`nx/ny/nz`), stopping once the next boundary is beyond `REACH`.
* `breakBlock()` — left button: sets the targeted cell to `0` (air).
* `placeBlock()` — right button: writes `SLOTS[sel]` into the **empty cell on the
  normal side** of the hit block (`(tx+nx, ty+ny, tz+nz)`, wrapped).
* `mouseButtonHandler(e)` — routes `mousedown` button 0 → destroy, button 2 → place;
  a `contextmenu` handler suppresses the browser menu on the canvas.

`pick()` is called once per frame in `clock()` (so the highlight tracks the aim)
and again inside `breakBlock`/`placeBlock`.

### [6] Mouse-wheel block selector + hotbar
* `wheelHandler(e)` — cycles `sel` through `SLOTS` (wrapping) on `wheel`.
* `drawHotbar()` — called each frame after the framebuffer blit; draws the slot
  backgrounds plus each block's 16×16 preview at the bottom of the canvas. The
  selected slot gets a bright border.
* The previews (`thumb[16]`) are built once in `init()` from the top tile of the
  texture atlas, so the panel shows the actual in-game block textures.

### [7] Crosshair (`drawCrosshair()`)
A 11-pixel white `+` drawn at the canvas centre, after `putImageData`, so it always
sits on top of the software-rendered frame.

### Render/loop changes
`clock()` now calls `pick()` before rendering, and `drawCrosshair()` /
`drawHotbar()` after `putImageData` (the overlays must come last because
`putImageData` overwrites the whole canvas).

## 13. New controls

| Input | Action |
|-------|--------|
| Move mouse | look (unchanged) |
| W / A / S / D | walk on the ground (see §15 for gravity/collision) |
| **Space** | jump (~1 block up, only while standing) |
| **Left mouse** | destroy the targeted block |
| **Right mouse** | place the selected block against the aimed face |
| **Mouse wheel** | scroll the hotbar selection |

## 14. Orientation note (important for the flat world)

In this engine's screen mapping, a ray toward the **bottom** of the screen has
`_yd > 0` and travels toward **increasing world Y**. The flat world therefore puts
the ground at **high Y** (≥ 48) and the player at a **low Y** (44.5) so the ground
renders below the horizon and the sky above it. The mouse-look handler is built the
same way: moving the cursor up makes `yRot` increase, which points the centre ray at
negative Y ("up on screen"). Any future terrain must respect this convention.

---

# Part 3 — Bug fixes and player physics

## 15. What changed in this pass (all tagged `[8]` in `4k.js`)

### 15.1 Bugfix — keyboard stopped working
The keys were listened for on the **canvas** (`canvas.addEventListener('keydown', …)`),
which only fires while the canvas has focus. Adding `e.preventDefault()` to the new
`mousedown` handler stopped the default focus-on-click, so after the first click the
canvas never regained focus and **no key ever reached the game**.

Fix: the listeners moved to the **document**, which always receives key events:
```js
document.addEventListener('keydown', handleInput, false);
document.addEventListener('keyup',   handleKeyUp,  false);
```
`handleInput` now only records `keys[event.keyCode] = true` and calls
`preventDefault()` for Space/arrows so the page does not scroll.

### 15.2 Visual fix — distance fog fades to the sky, not to black
The old shading multiplied the colour by a distance factor, so far blocks turned
black and left a hard dark band at the horizon. Now the lit colour is **lerped
toward the sky colour** for that pixel's ray:
```js
var fog = Math.min(closest / 32, 1);        // 0 near .. 1 at the cull distance
r = litR * (1 - fog) + skyR * fog;          // and g, b
```
Because the fog target is the same horizon→zenith gradient used for the background,
distant terrain dissolves smoothly into the sky.

### 15.3 Movement is now frame based (WASD)
Movement used to happen inside the `keydown` event (one step per OS key-repeat), which
is not tied to frame rate. It now runs once per frame in `update()`:
```js
if (keys[87]) f += 1;  // W
if (keys[83]) f -= 1;  // S
if (keys[68]) s += 1;  // D
if (keys[65]) s -= 1;  // A
```
using the same axes as the original (`forward = (xSin, xCos)`), at `SPEED = 0.1`
blocks/tick.

### 15.4 Gravity
Every frame: `vy += GRAV` (`GRAV = 0.02`), then `oy += vy`. Since **+Y is down**, a
positive `vy` is falling. The player starts at `oy = 44.5` and falls to the ground,
settling at `oy ≈ 46.30` (`onGround = true`).

### 15.5 Jump (≈1 block)
```js
if (keys[32] && onGround) { vy = JUMP; onGround = false; }   // JUMP = -0.21
```
With `GRAV = 0.02` this gives an apex of about **1 block** — enough to hop onto a
one-block step, like Minecraft.

### 15.6 Collision detection
The player is an axis-aligned box evaluated by `collides(cx, cy, cz)`:
half-width `0.3`, and vertically from `cy - 0.1` (head) to `cy + 1.7` (feet). Any
solid voxel in that box blocks movement.
* **Horizontal:** each of X and Z is tested and reverted separately, so walking into
  a block in front stops the player (the other axis still slides).
* **Vertical:** on the way **down** the feet are snapped onto the top of the block
  below (`oy = block - 1.7`); on the way **up** the head bumps and `vy` is zeroed.

`blockSolid(x,y,z)` reads the map with the same `& 63` wrap as the renderer.

Physics was verified headlessly: fall→stand, jump = 1.0 block apex, and a wall placed
1 block ahead stops forward motion (the box edge halts just short of the block).


