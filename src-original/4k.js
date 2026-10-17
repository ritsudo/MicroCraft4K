// ============================================================================
// Minecraft4k (JS) — enhanced build
// ----------------------------------------------------------------------------
// This is the original 4k.js software voxel raycaster with the following
// INSERTIONS (each block is tagged with a [n] banner):
//
//   [1] extra state: hotbar, reach, textures previews, target/normal storage
//   [2] flat terrain generation (replaces the random "chamber" world)
//   [3] blue-sky background, shaded by ray pitch
//   [4] targeted-block highlight (white wireframe box within REACH)
//   [5] block picking (centre-ray DDA) + left=destroy / right=place
//   [6] mouse-wheel block selector and on-canvas hotbar panel
//   [7] crosshair overlay
//
// Everything else (texture generation, ray caster, camera math) is unchanged.
// ============================================================================

var ctx;
var pixels;

var w = 212 * 2;
var h = 120 * 2;

var ox, oy, oz;
var xRot, yRot;

var yCos, ySin, xCos, xSin;

var map = new Array(64 * 64 * 64);
var texmap = new Array(16 * 16 * 3 * 16);

// [1] -----------------------------------------------------------------------
//     Extra state for building, selection, targeting and the sky.
// ---------------------------------------------------------------------------
var REACH = 8;                             // build / highlight distance in blocks
var SLOTS = [1, 4, 5, 6, 7, 8, 9];         // block ids offered in the hotbar
var sel = 1;                               // selected slot index (default: stone)
var thumb = new Array(16);                 // 16x16 preview ImageData per block id
var skyTop = [55, 115, 205];               // zenith colour
var skyBottom = [175, 212, 240];           // horizon colour

var tx = -1, ty = -1, tz = -1;             // targeted block (wrapped); -1 = none
var nx = 0, ny = 0, nz = 0;                // outward normal of the targeted face

// [8] -----------------------------------------------------------------------
//     Movement / physics state (all applied per frame in update()).
//     NOTE: +Y is "down" here, so gravity is positive and a jump is negative.
// ---------------------------------------------------------------------------
var keys = {};             // which keys are currently held down
var vy = 0;                // vertical velocity
var onGround = false;      // true while standing on a block
var GRAV = 0.02;           // gravity, blocks/tick²
var JUMP = -0.21;          // jump impulse (up = -Y); apex ≈ 1.1 blocks
var SPEED = 0.1;           // walk speed, blocks/tick

function init() {
	
	// TEXTURING
    for ( var i = 1; i < 16; i++) {
        var br = 255 - ((Math.random() * 96) | 0);
        for ( var y = 0; y < 16 * 3; y++) {
            for ( var x = 0; x < 16; x++) {
                var color = 0x966C4A;
                if (i == 4)
                    color = 0x7F7F7F;
                if (i != 4 || ((Math.random() * 3) | 0) == 0) {
                    br = 255 - ((Math.random() * 96) | 0);
                }
                if ((i == 1 && y < (((x * x * 3 + x * 81) >> 2) & 3) + 18)) {
                    color = 0x6AAA40;
                } else if ((i == 1 && y < (((x * x * 3 + x * 81) >> 2) & 3) + 19)) {
                    br = br * 2 / 3;
                }
                if (i == 7) {
                    color = 0x675231;
                    if (x > 0 && x < 15
                            && ((y > 0 && y < 15) || (y > 32 && y < 47))) {
                        color = 0xBC9862;
                        var xd = (x - 7);
                        var yd = ((y & 15) - 7);
                        if (xd < 0)
                            xd = 1 - xd;
                        if (yd < 0)
                            yd = 1 - yd;
                        if (yd > xd)
                            xd = yd;

                        br = 196 - ((Math.random() * 32) | 0) + xd % 3 * 32;
                    } else if (((Math.random() * 2) | 0) == 0) {
                        br = br * (150 - (x & 1) * 100) / 100;
                    }
                }

                if (i == 5) {
                    color = 0xB53A15;
                    if ((x + (y >> 2) * 4) % 8 == 0 || y % 4 == 0) {
                        color = 0xBCAFA5;
                    }
                }
                if (i == 9) {
                    color = 0x4040ff;
                }
                var brr = br;
                if (y >= 32)
                    brr /= 2;

                if (i == 8) {
                    color = 0x50D937;
                    if (((Math.random() * 2) | 0) == 0) {
                        color = 0;
                        brr = 255;
                    }
                }

                var col = (((color >> 16) & 0xff) * brr / 255) << 16
                        | (((color >> 8) & 0xff) * brr / 255) << 8
                        | (((color) & 0xff) * brr / 255);
                texmap[x + y * 16 + i * 256 * 3] = col;
            }
        }
    }

	// INITIALIZING CANVAS
	canvas = document.getElementById('game');
    ctx = canvas.getContext('2d');
	
	// [8] BUGFIX: keyboard is captured on the document instead of the canvas,
	//     so movement keeps working after a click (preventDefault on mousedown
	//     was stopping the canvas from ever receiving focus).
	document.addEventListener('keydown', handleInput, false);
	document.addEventListener('keyup', handleKeyUp, false);
	
	document.addEventListener("mousemove", mouseMoveHandler, false);

	// [5] block editing + [6] hotbar input
	canvas.addEventListener("mousedown", mouseButtonHandler, false);
	canvas.addEventListener("contextmenu", function (e) { e.preventDefault(); }, false);
	window.addEventListener("wheel", wheelHandler, { passive: false });

	// [1] build 16x16 preview icons for the hotbar from the generated atlas
	//     (top tile of each block slot, rows 0..15).
	for ( var b = 0; b < 16; b++) {
		thumb[b] = ctx.createImageData(16, 16);
		for ( var yy = 0; yy < 16; yy++) {
			for ( var xx = 0; xx < 16; xx++) {
				var c = texmap[xx + yy * 16 + b * 256 * 3];
				thumb[b].data[(xx + yy * 16) * 4 + 0] = (c >> 16) & 255;
				thumb[b].data[(xx + yy * 16) * 4 + 1] = (c >> 8) & 255;
				thumb[b].data[(xx + yy * 16) * 4 + 2] = c & 255;
				thumb[b].data[(xx + yy * 16) * 4 + 3] = 255;
			}
		}
	}

	// [2] LOAD MAP — flat ground instead of the random block array.
	//     NOTE: +Y reads as "down" on screen in this renderer, so the ground
	//     occupies the high-y half and the air/sky the low-y half.
	for ( var x = 0; x < 64; x++) {
		for ( var y = 0; y < 64; y++) {
			for ( var z = 0; z < 64; z++) {
				var i = z << 12 | y << 6 | x;
				if (y < 48)       map[i] = 0;   // air / sky
				else if (y == 48) map[i] = 1;   // grass surface layer
				else if (y < 51)  map[i] = 3;   // dirt
				else              map[i] = 4;   // stone
			}
		}
	}

	//BEGIN RENDERING
    pixels = ctx.createImageData(w, h);

    for ( var i = 0; i < w * h; i++) {
        pixels.data[i * 4 + 3] = 255;
    }

	//SET INTERVAL TO TICK METHOD
    setInterval(clock, 1000 / 50);
	
	//INITIALIZE PLAYER POSITION
	ox = 32.5;
	oy = 44.5;   // [2] stand a few blocks above the flat ground
	oz = 32.5;
	
	xRot = 0.0;
	yRot = 0.0;
};

function clock() {
    yCos = Math.cos(yRot);
    ySin = Math.sin(yRot);
    xCos = Math.cos(xRot);
    xSin = Math.sin(xRot);
	
	// [8] movement + gravity + collisions for this frame
	update();

	// [5] resolve the block under the crosshair for this frame
	pick();

    renderMinecraft();
    ctx.putImageData(pixels, 0, 0);

	// [7] + [6] overlays are painted on top of the software framebuffer
	drawCrosshair();
	drawHotbar();
};

function renderMinecraft() {
    for ( var x = 0; x < w; x++) {
        var ___xd = (x - w / 2) / h;
        for ( var y = 0; y < h; y++) {
            var __yd = (y - h / 2) / h;
            var __zd = 1;

            var ___zd = __zd * yCos + __yd * ySin;
            var _yd = __yd * yCos - __zd * ySin;

            var _xd = ___xd * xCos + ___zd * xSin;
            var _zd = ___zd * xCos - ___xd * xSin;

            var col = 0;
            var br = 255;

			// [4] info about the closest hit of this ray, for highlighting
			var hx = 0, hy = 0, hz = 0, hu = 0, hv = 0;

            var closest = 32;
            for ( var d = 0; d < 3; d++) {
                var dimLength = _xd;
                if (d == 1)
                    dimLength = _yd;
                if (d == 2)
                    dimLength = _zd;

                var ll = 1 / (dimLength < 0 ? -dimLength : dimLength);
                var xd = (_xd) * ll;
                var yd = (_yd) * ll;
                var zd = (_zd) * ll;

                var initial = ox - (ox | 0);
                if (d == 1)
                    initial = oy - (oy | 0);
                if (d == 2)
                    initial = oz - (oz | 0);
                if (dimLength > 0)
                    initial = 1 - initial;

                var dist = ll * initial;

                var xp = ox + xd * initial;
                var yp = oy + yd * initial;
                var zp = oz + zd * initial;

                if (dimLength < 0) {
                    if (d == 0)
                        xp--;
                    if (d == 1)
                        yp--;
                    if (d == 2)
                        zp--;
                }

                while (dist < closest) {
                    var tex = map[(zp & 63) << 12 | (yp & 63) << 6 | (xp & 63)];

                    if (tex > 0) {
                        var u = ((xp + zp) * 16) & 15;
                        var v = ((yp * 16) & 15) + 16;
                        if (d == 1) {
                            u = (xp * 16) & 15;
                            v = ((zp * 16) & 15);
                            if (yd < 0)
                                v += 32;
                        }

                        var cc = texmap[u + v * 16 + tex * 256 * 3];
                        if (cc > 0) {
                            col = cc;
                            br = 255 * (255 - ((d + 2) % 3) * 50) / 255;
                            closest = dist;
							// [4] remember voxel + local texel of the hit
							hx = xp & 63; hy = yp & 63; hz = zp & 63;
							hu = u; hv = v & 15;
                        }
                    }
                    xp += xd;
                    yp += yd;
                    zp += zd;
                    dist += ll;
                }
            }

			// [3] sky colour for this ray's pitch (also the [8] fog target below)
			var t = (1 - _yd) * 0.5;
			if (t < 0) t = 0;
			if (t > 1) t = 1;
			var sr = skyBottom[0] + (skyTop[0] - skyBottom[0]) * t;
			var sg = skyBottom[1] + (skyTop[1] - skyBottom[1]) * t;
			var sb = skyBottom[2] + (skyTop[2] - skyBottom[2]) * t;

			var r, g, b;
			if (col > 0) {
				// [8] FIX: fade far blocks toward the SKY colour (not black) so
				//     they dissolve into the horizon instead of going dark.
				var fog = closest / 32;
				if (fog > 1) fog = 1;

				r = ((col >> 16) & 0xff) * br / 255;
				g = ((col >> 8) & 0xff) * br / 255;
				b = ((col) & 0xff) * br / 255;

				r = r * (1 - fog) + sr * fog;
				g = g * (1 - fog) + sg * fog;
				b = b * (1 - fog) + sb * fog;

				// [4] highlight the targeted block: soften-brighten it and
				//     draw a white box around it via the texel border.
				if (tx >= 0 && hx === tx && hy === ty && hz === tz) {
					if (hu == 0 || hu == 15 || hv == 0 || hv == 15) {
						r = 255; g = 255; b = 230;
					} else {
						r += (255 - r) * 0.18;
						g += (255 - g) * 0.18;
						b += (255 - b) * 0.18;
					}
				}
			} else {
				// [3] BLUE SKY: lerp horizon -> zenith by the ray's pitch.
				r = sr;
				g = sg;
				b = sb;
			}

            pixels.data[(x + y * w) * 4 + 0] = r;
            pixels.data[(x + y * w) * 4 + 1] = g;
            pixels.data[(x + y * w) * 4 + 2] = b;
        }
    }
}

// [8] -----------------------------------------------------------------------
//     Frame-based movement, gravity, jumping and collision.
//     Keys only record their state here; update() does the actual moving so it
//     stays frame-rate consistent and can be collision checked.
// ---------------------------------------------------------------------------
function handleInput(event) {
	keys[event.keyCode] = true;
	if (event.keyCode === 32 || event.keyCode === 38 || event.keyCode === 40)
		event.preventDefault();   // stop the page scrolling on space / arrows
}

function handleKeyUp(event) {
	keys[event.keyCode] = false;
}

// solid block test, world wraps every 64
function blockSolid(x, y, z) {
	return map[(z & 63) << 12 | (y & 63) << 6 | (x & 63)] > 0;
}

// does the player box (feet to head) centred on eye position (cx,cy,cz) hit a block?
function collides(cx, cy, cz) {
	var x0 = Math.floor(cx - 0.3), x1 = Math.floor(cx + 0.3);
	var y0 = Math.floor(cy - 0.1), y1 = Math.floor(cy + 1.7);   // head .. feet (+Y down)
	var z0 = Math.floor(cz - 0.3), z1 = Math.floor(cz + 0.3);
	for (var x = x0; x <= x1; x++)
		for (var y = y0; y <= y1; y++)
			for (var z = z0; z <= z1; z++)
				if (blockSolid(x, y, z))
					return true;
	return false;
}

function update() {
	// 1) WASD walking (relative to the mouse yaw, same axes as the original)
	var f = 0, s = 0;
	if (keys[87]) f += 1;   // W forward
	if (keys[83]) f -= 1;   // S backward
	if (keys[68]) s += 1;   // D right
	if (keys[65]) s -= 1;   // A left
	if (f || s) {
		var nx = ox + (f * xSin + s * xCos) * SPEED;
		var nz = oz + (f * xCos - s * xSin) * SPEED;
		if (!collides(nx, oy, oz)) ox = nx;   // stop at a block in front
		if (!collides(ox, oy, nz)) oz = nz;
	}

	// 2) jump (only while standing)
	if (keys[32] && onGround) {
		vy = JUMP;
		onGround = false;
	}

	// 3) gravity
	vy += GRAV;
	if (vy > 0.5) vy = 0.5;

	// 4) vertical move with collision response
	var ny = oy + vy;
	if (collides(ox, ny, oz)) {
		if (vy > 0) {
			// falling: land on top of the block below
			var b = Math.floor(ny + 1.7);
			oy = b - 1.7 - 0.0001;
			onGround = true;
		} else {
			// rising: bump the head and drop back
			var tp = Math.floor(ny - 0.1);
			oy = tp + 1.1 + 0.0001;
			onGround = false;
		}
		vy = 0;
	} else {
		oy = ny;
		onGround = false;
	}
}

function mouseMoveHandler(e) {
	windowW = w*2;
	windowH = h*2;
	if (e.clientX < windowW && e.clientY < windowH) {
		xRot = 3.14 + e.clientX / 135;
		yRot = 1.57 - (e.clientY / 152);
	}
}

// [5] -----------------------------------------------------------------------
//     Pick the voxel under the screen centre with an Amanatides & Woo DDA.
//     The ray is built with exactly the same transform the renderer uses for
//     the centre pixel, so the crosshair and the highlight always agree.
//     On success sets tx/ty/tz (wrapped 0..63) and nx/ny/nz (face normal).
// ---------------------------------------------------------------------------
function pick() {
	tx = ty = tz = -1;
	nx = ny = nz = 0;

	var ___xd = (w / 2 - w / 2) / h;
	var __yd = (h / 2 - h / 2) / h;
	var __zd = 1;

	var ___zd = __zd * yCos + __yd * ySin;
	var _yd = __yd * yCos - __zd * ySin;
	var _xd = ___xd * xCos + ___zd * xSin;
	var _zd = ___zd * xCos - ___xd * xSin;

	var px = Math.floor(ox), py = Math.floor(oy), pz = Math.floor(oz);
	var sx = _xd < 0 ? -1 : 1;
	var sy = _yd < 0 ? -1 : 1;
	var sz = _zd < 0 ? -1 : 1;

	var dx = _xd === 0 ? Infinity : Math.abs(1 / _xd);
	var dy = _yd === 0 ? Infinity : Math.abs(1 / _yd);
	var dz = _zd === 0 ? Infinity : Math.abs(1 / _zd);

	var tmx = _xd === 0 ? Infinity : (sx > 0 ? px + 1 - ox : ox - px) * dx;
	var tmy = _yd === 0 ? Infinity : (sy > 0 ? py + 1 - oy : oy - py) * dy;
	var tmz = _zd === 0 ? Infinity : (sz > 0 ? pz + 1 - oz : oz - pz) * dz;

	for (var s = 0; s < 64; s++) {
		var t = Math.min(tmx, Math.min(tmy, tmz));
		if (t > REACH) return false;                      // out of reach

		if (tmx < tmy && tmx < tmz)      { px += sx; tmx += dx; nx = -sx; ny = 0; nz = 0; }
		else if (tmy < tmz)              { py += sy; tmy += dy; nx = 0; ny = -sy; nz = 0; }
		else                             { pz += sz; tmz += dz; nx = 0; ny = 0; nz = -sz; }

		var wx = px & 63, wy = py & 63, wz = pz & 63;
		if (map[wz << 12 | wy << 6 | wx] > 0) {
			tx = wx; ty = wy; tz = wz;
			return true;
		}
	}
	return false;
}

// [5] left mouse button — destroy the targeted block
function breakBlock() {
	if (pick() && tx >= 0)
		map[tz << 12 | ty << 6 | tx] = 0;
}

// [5] right mouse button — place the selected block against the hit face
function placeBlock() {
	if (!pick() || tx < 0)
		return;
	var wx = (tx + nx) & 63;
	var wy = (ty + ny) & 63;
	var wz = (tz + nz) & 63;
	var i = wz << 12 | wy << 6 | wx;
	if (map[i] === 0)
		map[i] = SLOTS[sel];
}

function mouseButtonHandler(e) {
	if (!(e.clientX < w * 2 && e.clientY < h * 2))
		return;
	if (e.button === 0)          // LEFT
		breakBlock();
	else if (e.button === 2)     // RIGHT
		placeBlock();
	e.preventDefault();
};

// [6] mouse wheel — scroll through the hotbar
function wheelHandler(e) {
	if (!(e.clientX < w * 2 && e.clientY < h * 2))
		return;
	sel = (sel + (e.deltaY > 0 ? 1 : -1) + SLOTS.length) % SLOTS.length;
	e.preventDefault();
}

// [7] crosshair drawn over the framebuffer
function drawCrosshair() {
	var cx = (w / 2) | 0;
	var cy = (h / 2) | 0;
	ctx.fillStyle = "rgba(255,255,255,0.85)";
	ctx.fillRect(cx - 5, cy, 11, 1);
	ctx.fillRect(cx, cy - 5, 1, 11);
}

// [6] hotbar panel with block previews and the current selection
function drawHotbar() {
	var n = SLOTS.length;
	var size = 20;
	var gap = 2;
	var totalW = n * size + (n - 1) * gap;
	var x0 = ((w - totalW) / 2) | 0;
	var y0 = h - size - 6;

	for (var k = 0; k < n; k++) {
		var sx = x0 + k * (size + gap);
		ctx.fillStyle = (k == sel) ? "rgba(255,255,255,0.9)" : "rgba(0,0,0,0.45)";
		ctx.fillRect(sx - 1, y0 - 1, size + 2, size + 2);
		ctx.fillStyle = "rgba(0,0,0,0.55)";
		ctx.fillRect(sx, y0, size, size);
		ctx.putImageData(thumb[SLOTS[k]], sx + 2, y0 + 2);
	}
}

init();
