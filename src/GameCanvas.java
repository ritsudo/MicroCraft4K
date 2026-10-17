import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.rms.RecordStore;
import java.util.Random;

/**
 * Portable J2ME port of the Minecraft4k JavaScript software voxel raycaster.
 *
 * <pre>
 * Key design points
 * -----------------
 *  - CLDC 1.1 / MIDP 2.0 only (Graphics.drawRGB + Image.createRGBImage).
 *  - Screen is capped at 240x320 (SE K800); the software framebuffer is
 *    rendered at a lower internal resolution (SCALE) and block-expanded into
 *    the screen buffer, which keeps the per-frame ray count low.
 *  - World is limited to 15 x 15 x 15 blocks (flat terrain), stored in a
 *    byte[] and sampled with plain integer maths.
 *  - The ray caster is a single-pass Amanatides &amp; Woo DDA written entirely
 *    in 16.16 fixed point (8.8 for the distance parameter) so no floating
 *    point runs in the inner loop.
 *  - Per-pixel ray directions are assembled from precomputed per-column /
 *    per-row tables, so a pixel costs a handful of integer adds instead of
 *    any trig or multiplication.
 *
 * Controls (Sony Ericsson K800 key codes)
 * ---------------------------------------
 *   W / S / A / D ................. 2 / 8 / 4 / 6   (walk)
 *   Mouse X (yaw) ................. joystick left / right
 *   Mouse Y (pitch) ............... joystick up / down
 *   Fast helper (break or place) .. joystick centre (see CENTER_MODE)
 *   Break block ................... left soft key
 *   Set block ..................... right soft key
 *   Next block .................... 3
 *   Previous block ................ 1
 *   Jump .......................... 5
 *   Pause / resume ............... * (star)
 *
 * The game boots into a menu (Play game / Help / Exit).  "Play game" opens a
 * three-slot level list backed by a single RMS RecordStore; a fresh slot
 * generates new terrain, a saved slot is reloaded.  The pause menu offers
 * Resume / Save world / Load world / Graphics (Fancy|Medium|Fast) / Exit.
 * </pre>
 */
public class GameCanvas extends Canvas implements Runnable {

    // ------------------------------------------------------------------
    // World
    // ------------------------------------------------------------------
    /** World is limited to WORLD x WORLD x WORLD blocks. */
    static final int WORLD = 15;
    static final int WORLD2 = WORLD * WORLD;
    static final int WORLD3 = WORLD * WORLD * WORLD;

    // The voxel map is stored with a 1-cell OUTSIDE shell around the real world,
    // so the raycaster needs no per-step bounds test: a ray that leaves the
    // world simply hits the sentinel and stops.  This removes six comparisons
    // and the two index multiplies from the hottest inner loop.
    static final int PAD = 1;
    static final int SXY = WORLD + 2 * PAD;      // padded size per axis (17)
    static final int PSY = SXY;                  // padded y stride
    static final int PSZ = SXY * SXY;            // padded z stride (289)
    static final int PLEN = SXY * SXY * SXY;     // padded map length (4913)
    static final byte OUTSIDE = 127;             // "left the world" sentinel
    final byte[] map = new byte[PLEN];

    /** Flat index into the padded map for a world voxel. */
    private static int mi(int x, int y, int z) {
        return (x + PAD) + (y + PAD) * PSY + (z + PAD) * PSZ;
    }

    // ------------------------------------------------------------------
    // Texture atlas: 16 slots, each 16 wide x 48 tall (3 stacked tiles),
    // stored as packed 0xRRGGBB integers: texmap[x + y*16 + tex*768].
    // ------------------------------------------------------------------
    static final int TEX_STRIDE = 1024;             // 1<<10 -> index by shift
    static final int TEX_LEN = TEX_STRIDE * 16;     // 16384
    final int[] texmap = new int[TEX_LEN];
    final Image[] thumb = new Image[16];            // 16x16 hotbar previews
    // Pre-shaded colour table: SHADE[light*256 + c] = c * LIGHT / 255.  Replaces
    // three per-pixel divisions (divide-by-255) in the inner shading code.
    final int[] SHADE = new int[3 * 256];

    // ------------------------------------------------------------------
    // Framebuffer / screen
    //
    // The playable view is a fixed square: GAME x GAME on screen, rendered
    // internally at `scale` x `scale` lower resolution (each memory pixel fills
    // a scale x scale block), so the raycaster cost never depends on the
    // handset's screen height.  The free bands above/below the square are
    // painted once with a darkened ground texture and afterwards only blitted;
    // the UI on them is refreshed only when it actually changes.
    // ------------------------------------------------------------------
    static final int GAME = 240;                // on-screen game square (px)
    static final int MAXRW = GAME / 2;          // largest internal render size
    // Internal resolution divider (2..4), switchable at runtime with key 7 so
    // the detail / speed trade-off can be tuned on the handset: each step up
    // multiplies the number of cast rays (and the frame cost) by scale*scale.
    volatile int scale = 3;
    int rw, rh;                                 // current internal render size
    volatile int pendingScale;                  // applied on the game thread

    int screenW, screenH;                       // device screen (capped)
    int gameX, gameY;                           // top-left of the game square
    final int[] buf = new int[GAME * GAME];     // fixed framebuffer 240x240
    Image bandTop, bandBot;                     // cached UI bands (dark ground)
    Image darkTile;                             // 16x16 darkened ground tile
    volatile boolean uiDirty;                   // rebuild bottom band (e.g. sel)
    static final int BAND_DARK = 0x2A3A1C;      // fill for uncovered edges

    // ------------------------------------------------------------------
    // Camera / player  (+Y is "down", so gravity is positive)
    // ------------------------------------------------------------------
    float ox, oy, oz;               // position, blocks
    float yaw, pitch;               // radians (yaw around Y, pitch up/down)
    float vy;                       // vertical velocity
    boolean onGround;

    static final float GRAV = 0.02f;
    static final float JUMP = -0.21f;   // apex ~1 block
    static final float SPEED = 0.1f;    // blocks / tick
    static final float TURN = 0.07f;    // radians / tick (look)

    // ------------------------------------------------------------------
    // Fixed point
    // ------------------------------------------------------------------
    static final int FIX = 16;
    static final int ONE = 1 << FIX;    // 1.0 in 16.16
    static final int MINDIR = 256;      // ignore axis components below 1/256
    static final int BIGT = 1 << 29;    // "infinite" DDA step
    static final int VIEW = 32;         // view distance (blocks); 2^13 when <<8,
    static final int VIEW8 = VIEW * 256; // so fog becomes a shift, not a divide
    static final int REACH = 6;         // build / highlight reach (blocks)
    static final int REACH8 = REACH * 256;

    int camXf, camYf, camZf;            // camera position, 16.16
    int pcx, pcy, pcz;                  // camera voxel (floor)
    int rayK1, rayK3, raySP;            // per-frame ray constants
    int[] dXr, dYr;                     // camera-space ray dir per column/row
    int[] ax, bx, ay, by, cy;           // per-frame projection tables

    static final int[] LIGHT = {155, 255, 205};
    static final int SKYT_R = 55,  SKYT_G = 115, SKYT_B = 205;
    static final int SKYB_R = 175, SKYB_G = 212, SKYB_B = 240;

    // ------------------------------------------------------------------
    // Targeting / blocks
    // ------------------------------------------------------------------
    int tx = -1, ty = -1, tz = -1;      // picked voxel, -1 = none
    int nx = 0, ny = 0, nz = 0;         // outward face normal of picked voxel

    static final int[] SLOTS = {1, 4, 5, 6, 7, 8, 9};
    int sel = 1;                        // selected slot index (stone)

    // Raw device key codes used by the Sony Ericsson / Sun WTK emulator
    // keyboard handler.  These are NOT the positive Canvas game-action
    // constants: the joystick arrives as negative codes, which is why an
    // earlier version that compared against Canvas.UP/DOWN/LEFT/RIGHT
    // received nothing.  Detected by logging in the WTK K800 emulator:
    //   up = -1, down = -2, left = -3, right = -4, select/centre = -5.
    static final int JOY_UP    = -1;
    static final int JOY_DOWN  = -2;
    static final int JOY_LEFT  = -3;
    static final int JOY_RIGHT = -4;
    static final int JOY_FIRE  = -5;    // joystick centre / select
    static final int SOFT1     = -6;    // left soft key  -> break block
    static final int SOFT2     = -7;    // right soft key -> set block

    /** Fast-helper mode for the joystick centre: 0 = break, 1 = place. */
    static final int CENTER_MODE = 0;

    // ------------------------------------------------------------------
    // Input state
    // ------------------------------------------------------------------
    boolean kFwd, kBack, kLeft, kRight;
    boolean kLookL, kLookR, kLookU, kLookD;
    boolean kJump;

    // ------------------------------------------------------------------
    // Loop / stats
    // ------------------------------------------------------------------
    final Minecraft4k midlet;
    Thread thread;
    volatile boolean running;
    int fps;
    // Incremented (volatile) at the END of every render pass: the write
    // publishes the just-written framebuffer pixels to other threads, so the
    // UI thread's blit sees real data instead of a stale all-zero buffer.
    volatile int renderCount;
    int renderStamp;                // last value of renderCount read by paint
    volatile boolean sized;         // published by whichever thread sizes the buffers

    // ------------------------------------------------------------------
    // Screens / menus
    // ------------------------------------------------------------------
    static final int ST_MENU = 0, ST_HELP = 1, ST_LEVELS = 2, ST_PLAY = 3, ST_PAUSE = 4;
    volatile int state = ST_MENU;
    int menuSel = 0;                // 0 Play game, 1 Help, 2 Exit
    int helpReturn = ST_MENU;       // where Help returns to
    int levelSel = 0;               // selected level slot
    boolean trashFocus = false;     // joystick-right focus on the trash icon
    boolean confirmDelete = false;  // awaiting delete confirmation
    int pauseSel = 0;               // 0 Resume 1 Save 2 Load 3 Graphics 4 Exit
    int curLevel = 0;               // level currently being played

    static final String MENU_TITLE = "MicroCraft";
    static final String[] MENU_ITEMS = {"Play game", "Help", "Exit"};
    static final String[] PAUSE_ITEMS = {"Resume", "Save world", "Load world",
            "Graphics", "Exit to main menu"};
    static final String[] GRAPH_NAMES = {"Fancy", "Medium", "Fast"};

    // ------------------------------------------------------------------
    // Save storage (RecordStore)
    // ------------------------------------------------------------------
    static final String RS_NAME = "MicroCraft";
    static final int MAX_LEVELS = 3;
    int maxSlots = MAX_LEVELS;      // may drop to 2 or 1 on small RMS
    final boolean[] levelExists = new boolean[MAX_LEVELS];
    static final int LEVEL_BYTES = WORLD3 + 6 * 4;    // one level: world + 6 floats
    Image bgImage;                  // cached menu background (dark ground)

    private final Random rnd = new Random(0x4d43344bL); // "MC4K"

    public GameCanvas(Minecraft4k midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
        generateTextures();
        initFixedBuffers();
        detectStorage();
        ox = WORLD / 2f;      // 7.5
        oy = WORLD / 2f;      // spawn a little above the ground plane
        oz = WORLD / 2f;
        yaw = 0f;             // look toward +Z
        pitch = -0.15f;       // slightly down
        state = ST_MENU;
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================
    void start() {
        if (thread == null) {
            running = true;
            thread = new Thread(this);
            thread.start();
        }
    }

    void stop() {
        running = false;
        thread = null;
    }

    public void run() {
        long lastFps = System.currentTimeMillis();
        int frames = 0;
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                // Only the playing screen advances the world; menus are static
                // and the pause screen keeps the last rendered frame.
                if (state == ST_PLAY) {
                    update();
                    render();
                }
                repaint();
                serviceRepaints();
            } catch (Throwable t) {
                // keep the loop alive on any transient error
            }
            frames++;
            long now = System.currentTimeMillis();
            if (now - lastFps >= 1000) {
                fps = frames;
                frames = 0;
                lastFps = now;
            }
            long dt = System.currentTimeMillis() - t0;
            long sleep = 20 - dt;            // ~50 fps target
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { }
            }
        }
    }

    // ==================================================================
    // Screen size / buffers
    // ==================================================================
    /**
     * Allocates the fixed-size render buffers and ray-basis tables.  These are
     * allocated once (at the maximum size) but re-derived whenever the detail
     * scale changes; the game square is always GAME x GAME.
     */
    private void initFixedBuffers() {
        if (dXr == null) {
            dXr = new int[MAXRW];
            dYr = new int[MAXRW];
            ax = new int[MAXRW];
            bx = new int[MAXRW];
            ay = new int[MAXRW];
            by = new int[MAXRW];
            cy = new int[MAXRW];
        }
        rw = GAME / scale;
        rh = GAME / scale;

        // FOV: divide the pixel offset by rh so the square view keeps the same
        // field of view for every scale (half-angle atan(0.5) ~= 26.5 deg).
        double halfW = rw / 2.0;
        double halfH = rh / 2.0;
        for (int i = 0; i < rw; i++) {
            dXr[i] = (int)(((i - halfW) / rh) * ONE);
        }
        for (int j = 0; j < rh; j++) {
            dYr[j] = (int)(((j - halfH) / rh) * ONE);
        }
    }

    /**
     * Recomputes the layout for the (possibly capped) handset screen and
     * rebuilds the cached UI bands when the layout actually changes.
     */
    private void ensureSize() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Cap generously (U10i is 240x432) BEFORE the "anything changed?" test
        // so a real height above the cap can never trigger a per-paint rebuild.
        if (w > 240) w = 240;
        if (h > 480) h = 480;
        if (sized && w == screenW && h == screenH) return;

        screenW = w;
        screenH = h;
        gameX = (screenW - GAME) / 2;
        if (gameX < 0) gameX = 0;
        gameY = (screenH - GAME) / 2;
        if (gameY < 0) gameY = 0;

        buildBands();
        buildBackground();
        sized = true;
    }

    /** Builds both UI bands (the free strips above and below the game square). */
    private void buildBands() {
        bandTop = makeBand(screenW, gameY, false);
        bandBot = makeBand(screenW, screenH - (gameY + GAME), true);
    }

    /** Cached tiled dark-ground background for the menu screens. */
    private void buildBackground() {
        try {
            bgImage = Image.createImage(screenW, screenH);
        } catch (Throwable t) {
            bgImage = null;
            return;
        }
        Graphics g = bgImage.getGraphics();
        g.setColor(BAND_DARK);
        g.fillRect(0, 0, screenW, screenH);
        if (darkTile != null) {
            for (int y = 0; y < screenH; y += 16) {
                for (int x = 0; x < screenW; x += 16) {
                    g.drawImage(darkTile, x, y, Graphics.TOP | Graphics.LEFT);
                }
            }
        }
    }

    /** Rebuilds only the bottom band, e.g. after the selected block changed. */
    private void buildBandBot() {
        bandBot = makeBand(screenW, screenH - (gameY + GAME), true);
    }

    /**
     * Paints one UI band: fills it with the darkened ground texture (tiled from
     * the cached darkTile) and, for the bottom band, the hotbar and labels.
     */
    private Image makeBand(int w, int h, boolean withHud) {
        if (w <= 0 || h <= 0) return null;
        Image img;
        try {
            img = Image.createImage(w, h);
        } catch (Throwable t) {
            return null;
        }
        Graphics g = img.getGraphics();
        g.setColor(BAND_DARK);
        g.fillRect(0, 0, w, h);
        if (darkTile != null) {
            for (int y = 0; y < h; y += 16) {
                for (int x = 0; x < w; x += 16) {
                    g.drawImage(darkTile, x, y, Graphics.TOP | Graphics.LEFT);
                }
            }
        }
        if (withHud) drawHotbarInto(g, w, h);
        return img;
    }

    /** Draws the hotbar + soft-key labels into a band image. */
    private void drawHotbarInto(Graphics g, int w, int h) {
        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(font);

        int n = SLOTS.length;
        int sz = 24, gap = 2;
        int total = n * sz + (n - 1) * gap;
        int x0 = (w - total) / 2;

        int contentH = sz + 2 + 11;
        int top = (h - contentH) / 2;
        if (top < 2) top = 2;
        int y0 = top;

        for (int k = 0; k < n; k++) {
            int sx = x0 + k * (sz + gap);
            g.setColor(k == sel ? 0xFFFFFF : 0x000000);
            g.drawRect(sx - 1, y0 - 1, sz + 1, sz + 1);
            g.setColor(0x303030);
            g.fillRect(sx, y0, sz, sz);
            Image im = thumb[SLOTS[k]];
            if (im != null) {
                g.drawImage(im, sx + (sz - 16) / 2, y0 + (sz - 16) / 2,
                        Graphics.TOP | Graphics.LEFT);
            }
        }

        int ly = y0 + sz + 2;
        g.setColor(0x000000);
        g.drawString("Break", 3, ly, Graphics.TOP | Graphics.LEFT);
        g.drawString("Set", w - 3, ly, Graphics.TOP | Graphics.RIGHT);
        g.drawString("blk " + SLOTS[sel] + (CENTER_MODE == 0 ? " brk" : " set"),
                w / 2, ly, Graphics.TOP | Graphics.HCENTER);
    }

    // ==================================================================
    // Asset generation (ported from src-original/4k.js init())
    // ==================================================================
    private static int rand(Random r, int n) {
        return r.nextInt(n);
    }

    private void generateTextures() {
        for (int i = 1; i < 16; i++) {
            int br = 255 - rand(rnd, 96);
            for (int y = 0; y < 16 * 3; y++) {
                for (int x = 0; x < 16; x++) {
                    int color = 0x966C4A;
                    if (i == 4) color = 0x7F7F7F;
                    if (i != 4 || rand(rnd, 3) == 0) {
                        br = 255 - rand(rnd, 96);
                    }
                    int ridge = (((x * x * 3 + x * 81) >> 2) & 3) + 18;
                    if (i == 1 && y < ridge) {
                        color = 0x6AAA40;
                    } else if (i == 1 && y < ridge + 1) {
                        br = br * 2 / 3;
                    }
                    if (i == 7) {
                        color = 0x675231;
                        if (x > 0 && x < 15 && ((y > 0 && y < 15) || (y > 32 && y < 47))) {
                            color = 0xBC9862;
                            int xd = x - 7;
                            int yd = (y & 15) - 7;
                            if (xd < 0) xd = 1 - xd;
                            if (yd < 0) yd = 1 - yd;
                            if (yd > xd) xd = yd;
                            br = 196 - rand(rnd, 32) + xd % 3 * 32;
                        } else if (rand(rnd, 2) == 0) {
                            br = br * (150 - (x & 1) * 100) / 100;
                        }
                    }
                    if (i == 5) {
                        color = 0xB53A15;
                        if ((x + (y >> 2) * 4) % 8 == 0 || y % 4 == 0) {
                            color = 0xBCAFA5;
                        }
                    }
                    if (i == 9) color = 0x4040ff;

                    int brr = br;
                    if (y >= 32) brr /= 2;

                    if (i == 8) {
                        color = 0x50D937;
                        if (rand(rnd, 2) == 0) {
                            color = 0;
                            brr = 255;
                        }
                    }

                    int col = ((((color >> 16) & 0xff) * brr / 255) << 16)
                            | ((((color >> 8) & 0xff) * brr / 255) << 8)
                            | (((color) & 0xff) * brr / 255);
                    texmap[x + y * 16 + i * TEX_STRIDE] = col;
                }
            }
        }

        // 16x16 hotbar previews from the top tile of each slot.
        int[] px = new int[16 * 16];
        for (int b = 0; b < 16; b++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int xx = 0; xx < 16; xx++) {
                    px[xx + yy * 16] = texmap[xx + yy * 16 + b * TEX_STRIDE] & 0xFFFFFF;
                }
            }
            try {
                thumb[b] = Image.createRGBImage(px, 16, 16, false);
            } catch (Throwable t) {
                thumb[b] = null;
            }
        }

        // Darkened grass tile used to fill the UI bands (the "free" screen
        // space around the square game area).
        int[] dp = new int[16 * 16];
        for (int yy = 0; yy < 16; yy++) {
            for (int xx = 0; xx < 16; xx++) {
                int c = texmap[xx + yy * 16 + 1 * TEX_STRIDE];
                int r = ((c >> 16) & 255) * 55 / 100;
                int gg = ((c >> 8) & 255) * 55 / 100;
                int b2 = (c & 255) * 55 / 100;
                dp[xx + yy * 16] = (r << 16) | (gg << 8) | b2;
            }
        }
        try {
            darkTile = Image.createRGBImage(dp, 16, 16, false);
        } catch (Throwable t) {
            darkTile = null;
        }

        // Pre-shaded colour table (one row per face light level).
        for (int k = 0; k < 3; k++) {
            int li = LIGHT[k];
            for (int c = 0; c < 256; c++) {
                SHADE[k * 256 + c] = c * li / 255;
            }
        }
    }

    // Block ids produced by the terrain generator.
    static final int B_AIR = 0, B_GRASS = 1, B_DIRT = 3, B_STONE = 4,
                     B_WOOD = 7, B_LEAF = 8, B_WATER = 9;

    /**
     * Primitive terrain generator: gentle hills (+/-2 around the horizon at
     * y = 10), one small lake of water blocks, and a single tree.  +Y is down,
     * so the surface is the smallest y of each column.
     */
    private void generateWorld(int level) {
        // Everything starts as the OUTSIDE sentinel; the real blocks are then
        // written into the interior of the padded map.
        for (int i = 0; i < PLEN; i++) map[i] = OUTSIDE;

        Random r = new Random(0x4d43344bL + level * 0x9E3779B1L);
        final int H0 = 10;              // horizon layer
        final int LO = H0 - 2, HI = H0 + 2;

        // --- hills ---
        int[][] gh = new int[WORLD][WORLD];
        for (int z = 0; z < WORLD; z++)
            for (int x = 0; x < WORLD; x++) gh[x][z] = H0;
        for (int i = 0; i < 5; i++) {
            int cx = r.nextInt(WORLD), cz = r.nextInt(WORLD);
            int amp = r.nextInt(2) == 0 ? 1 : -1;
            int rad = 2 + r.nextInt(2);
            for (int dz = -rad; dz <= rad; dz++) {
                for (int dx = -rad; dx <= rad; dx++) {
                    int xx = cx + dx, zz = cz + dz;
                    if (xx < 0 || xx >= WORLD || zz < 0 || zz >= WORLD) continue;
                    int d = Math.max(dx < 0 ? -dx : dx, dz < 0 ? -dz : dz);
                    gh[xx][zz] += amp * (rad - d);
                }
            }
        }

        // --- lake (flat water basin, two layers deep) ---
        int lx = 2 + r.nextInt(WORLD - 4), lz = 2 + r.nextInt(WORLD - 4);
        final int LR = 2;
        for (int dz = -LR; dz <= LR; dz++) {
            for (int dx = -LR; dx <= LR; dx++) {
                int xx = lx + dx, zz = lz + dz;
                if (xx < 0 || xx >= WORLD || zz < 0 || zz >= WORLD) continue;
                if (dx * dx + dz * dz <= LR * LR) gh[xx][zz] = H0;
            }
        }

        // --- write the columns ---
        for (int z = 0; z < WORLD; z++) {
            for (int x = 0; x < WORLD; x++) {
                int h = gh[x][z];
                if (h < LO) h = LO;
                if (h > HI) h = HI;
                boolean lake = (x - lx) * (x - lx) + (z - lz) * (z - lz) <= LR * LR;
                for (int y = 0; y < WORLD; y++) {
                    int id;
                    if (y < h)           id = B_AIR;
                    else if (y == h)     id = lake ? B_WATER : B_GRASS;
                    else if (y == h + 1) id = lake ? B_WATER : B_DIRT;
                    else if (y < h + 3)  id = B_DIRT;
                    else                 id = B_STONE;
                    map[mi(x, y, z)] = (byte) id;
                }
            }
        }

        // --- a single tree on dry land ---
        for (int tries = 0; tries < 40; tries++) {
            int x = 1 + r.nextInt(WORLD - 2), z = 1 + r.nextInt(WORLD - 2);
            if ((x - lx) * (x - lx) + (z - lz) * (z - lz) <= (LR + 1) * (LR + 1)) {
                continue;               // keep it out of the lake
            }
            int th = gh[x][z];
            if (th < LO) th = LO; else if (th > HI) th = HI;
            for (int k = 1; k <= 3; k++) put(x, th - k, z, B_WOOD);
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) put(x + dx, th - 4, z + dz, B_LEAF);
            put(x - 1, th - 3, z, B_LEAF);
            put(x + 1, th - 3, z, B_LEAF);
            put(x, th - 3, z - 1, B_LEAF);
            put(x, th - 3, z + 1, B_LEAF);
            break;
        }

        spawnPlayer();
    }

    /** Writes a single block, ignoring out-of-world coordinates. */
    private void put(int x, int y, int z, int id) {
        if (x < 0 || x >= WORLD || y < 0 || y >= WORLD || z < 0 || z >= WORLD) return;
        map[mi(x, y, z)] = (byte) id;
    }

    /** y of the first solid block in a column (the surface). */
    private int surfaceY(int x, int z) {
        for (int y = 0; y < WORLD; y++) {
            byte v = map[mi(x, y, z)];
            if (v > 0 && v != OUTSIDE) return y;
        }
        return WORLD - 1;
    }

    /** Drops the player just above the middle of the map. */
    private void spawnPlayer() {
        int cx = WORLD / 2, cz = WORLD / 2;
        ox = cx + 0.5f;
        oz = cz + 0.5f;
        oy = surfaceY(cx, cz) - 2f;
        if (oy < 0.5f) oy = 0.5f;
        vy = 0f;
        yaw = 0f;
        pitch = -0.15f;
        onGround = false;
    }

    // ==================================================================
    // Per-frame preparation
    // ==================================================================
    private static int fm(int a, int b) {
        return (int)(((long) a * b) >> FIX);
    }

    private void prepare() {
        float cyaw = (float) Math.cos(yaw), syaw = (float) Math.sin(yaw);
        float cpit = (float) Math.cos(pitch), spit = (float) Math.sin(pitch);
        int cyawF = (int)(cyaw * ONE), syawF = (int)(syaw * ONE);
        int cpitF = (int)(cpit * ONE), spitF = (int)(spit * ONE);

        rayK1 = fm(cpitF, syawF);       // cos(pitch) * sin(yaw)
        int k2 = fm(spitF, syawF);      // sin(pitch) * sin(yaw)
        rayK3 = fm(cpitF, cyawF);       // cos(pitch) * cos(yaw)
        int k4 = fm(spitF, cyawF);      // sin(pitch) * cos(yaw)
        raySP = spitF;                  // sin(pitch)

        for (int i = 0; i < rw; i++) {
            ax[i] = fm(dXr[i], cyawF);
            bx[i] = fm(dXr[i], syawF);
        }
        for (int j = 0; j < rh; j++) {
            ay[j] = fm(dYr[j], k2);
            by[j] = fm(dYr[j], k4);
            cy[j] = fm(dYr[j], cpitF);
        }

        camXf = (int)(ox * ONE);
        camYf = (int)(oy * ONE);
        camZf = (int)(oz * ONE);
        pcx = (int) Math.floor(ox);
        pcy = (int) Math.floor(oy);
        pcz = (int) Math.floor(oz);
    }

    // ==================================================================
    // Physics / input update
    // ==================================================================
    private boolean solid(int x, int y, int z) {
        // The world edges act as walls / floor so the player can never walk off
        // the map and fall into the void.
        if (x < 0 || x >= WORLD || z < 0 || z >= WORLD) return true;
        if (y < 0 || y >= WORLD) return true;
        return map[mi(x, y, z)] > 0;
    }

    private boolean collides(float cx, float cy, float cz) {
        int x0 = (int) Math.floor(cx - 0.3f), x1 = (int) Math.floor(cx + 0.3f);
        int y0 = (int) Math.floor(cy - 0.1f), y1 = (int) Math.floor(cy + 1.7f);
        int z0 = (int) Math.floor(cz - 0.3f), z1 = (int) Math.floor(cz + 0.3f);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (solid(x, y, z)) return true;
                }
            }
        }
        return false;
    }

    private void update() {
        // Apply a pending detail (resolution) change requested from the UI
        // thread, rebuilding the ray-basis tables at the new scale.
        int ps = pendingScale;
        if (ps > 0) {
            pendingScale = 0;
            scale = ps;
            initFixedBuffers();
        }

        // look (joystick)
        if (kLookL) yaw -= TURN;
        if (kLookR) yaw += TURN;
        if (kLookU) pitch += TURN;
        if (kLookD) pitch -= TURN;
        if (pitch > 1.55f) pitch = 1.55f;
        if (pitch < -1.55f) pitch = -1.55f;
        if (yaw > 6.2831853f) yaw -= 6.2831853f;
        if (yaw < 0f) yaw += 6.2831853f;

        // walk (2/8/4/6), relative to yaw
        float f = 0f, s = 0f;
        if (kFwd) f += 1f;
        if (kBack) f -= 1f;
        if (kRight) s += 1f;
        if (kLeft) s -= 1f;
        if (f != 0f || s != 0f) {
            float cyaw = (float) Math.cos(yaw), syaw = (float) Math.sin(yaw);
            float nxp = ox + (f * syaw + s * cyaw) * SPEED;
            float nzp = oz + (f * cyaw - s * syaw) * SPEED;
            if (!collides(nxp, oy, oz)) ox = nxp;
            if (!collides(ox, oy, nzp)) oz = nzp;
        }

        // jump + gravity
        if (kJump && onGround) {
            vy = JUMP;
            onGround = false;
        }
        vy += GRAV;
        if (vy > 0.5f) vy = 0.5f;

        float ny2 = oy + vy;
        if (collides(ox, ny2, oz)) {
            if (vy > 0f) {
                int b = (int) Math.floor(ny2 + 1.7f);
                oy = b - 1.7f - 0.0001f;
                onGround = true;
            } else {
                int tp = (int) Math.floor(ny2 - 0.1f);
                oy = tp + 1.1f + 0.0001f;
                onGround = false;
            }
            vy = 0f;
        } else {
            oy = ny2;
            onGround = false;
        }

        prepare();
        pick();
    }

    // ==================================================================
    // Targeting (single-ray DDA along the exact centre ray)
    // ==================================================================
    private void pick() {
        tx = ty = tz = -1;
        nx = ny = nz = 0;

        int xd = rayK1, zd = rayK3, yd = -raySP;

        int px = pcx, py = pcy, pz = pcz;
        int adx = xd < 0 ? -xd : xd;
        int ady = yd < 0 ? -yd : yd;
        int adz = zd < 0 ? -zd : zd;
        int tdx = adx < MINDIR ? BIGT : (1 << 24) / adx;
        int tdy = ady < MINDIR ? BIGT : (1 << 24) / ady;
        int tdz = adz < MINDIR ? BIGT : (1 << 24) / adz;
        int sx, sy, sz, tmx, tmy, tmz;
        if (xd > 0)      { sx = 1;  tmx = (int)(((long)((px + 1) << FIX) - camXf) >> 8); }
        else if (xd < 0) { sx = -1; tmx = (int)(((long) camXf - (px << FIX)) >> 8); }
        else             { sx = 0;  tmx = 0; }
        if (yd > 0)      { sy = 1;  tmy = (int)(((long)((py + 1) << FIX) - camYf) >> 8); }
        else if (yd < 0) { sy = -1; tmy = (int)(((long) camYf - (py << FIX)) >> 8); }
        else             { sy = 0;  tmy = 0; }
        if (zd > 0)      { sz = 1;  tmz = (int)(((long)((pz + 1) << FIX) - camZf) >> 8); }
        else if (zd < 0) { sz = -1; tmz = (int)(((long) camZf - (pz << FIX)) >> 8); }
        else             { sz = 0;  tmz = 0; }
        tmx = tdx >= BIGT ? BIGT : ((tmx * tdx) >> 8);
        tmy = tdy >= BIGT ? BIGT : ((tmy * tdy) >> 8);
        tmz = tdz >= BIGT ? BIGT : ((tmz * tdz) >> 8);

        int idx = mi(px, py, pz);
        int dIdxY = sy * PSY, dIdxZ = sz * PSZ;
        for (int step = 0; step < 64; step++) {
            int tmin, axis;
            if (tmx < tmy) { tmin = tmx; axis = 0; }
            else           { tmin = tmy; axis = 1; }
            if (tmz < tmin) { tmin = tmz; axis = 2; }
            if (tmin > REACH8) return;

            if (axis == 0)      { px += sx; tmx += tdx; idx += sx;    nx = -sx; ny = 0;   nz = 0;   }
            else if (axis == 1) { py += sy; tmy += tdy; idx += dIdxY; nx = 0;   ny = -sy; nz = 0;   }
            else                { pz += sz; tmz += tdz; idx += dIdxZ; nx = 0;   ny = 0;   nz = -sz; }

            int v = map[idx];
            if (v == OUTSIDE) return;
            if (v > 0) {
                tx = px; ty = py; tz = pz;
                return;
            }
        }
    }

    // ==================================================================
    // Block editing
    // ==================================================================
    private void breakBlock() {
        pick();
        if (tx >= 0) {
            map[mi(tx, ty, tz)] = 0;
        }
    }

    private void placeBlock() {
        pick();
        if (tx < 0) return;
        int wx = tx + nx, wy = ty + ny, wz = tz + nz;
        if (wx < 0 || wx >= WORLD || wy < 0 || wy >= WORLD || wz < 0 || wz >= WORLD) {
            return;
        }
        int i = mi(wx, wy, wz);
        if (map[i] == 0) {
            map[i] = (byte) SLOTS[sel];
        }
    }

    private void centerHelper() {
        if (CENTER_MODE == 0) breakBlock();
        else placeBlock();
    }

    private void nextBlock() {
        sel = (sel + 1) % SLOTS.length;
        uiDirty = true;
    }

    private void prevBlock() {
        sel = (sel + SLOTS.length - 1) % SLOTS.length;
        uiDirty = true;
    }

    /** Cycles the internal render resolution (2 = sharpest, 4 = fastest). */
    private void cycleDetail() {
        int s = scale + 1;
        if (s > 4) s = 2;
        if (state == ST_PLAY) {
            pendingScale = s;       // applied on the game thread in update()
        } else {
            scale = s;              // menus / pause: apply immediately
            initFixedBuffers();
        }
    }

    // ==================================================================
    // Rendering
    // ==================================================================
    private void render() {
        // The framebuffer and ray tables are allocated once at construction, so
        // render() never depends on the handset screen or on ensureSize().
        int[] fb = buf;

        for (int iy = 0; iy < rh; iy++) {
            int ayy = ay[iy];
            int byy = by[iy];
            int yd = cy[iy] - raySP;
            int zdBase = rayK3 + byy;
            int rowOut = (iy * scale) * GAME;
            boolean ydNeg = yd < 0;

            // sky colour for this row is close to constant across x (only yd
            // matters), so compute it once per row.
            int tt = (ONE - yd) >> 1;
            if (tt < 0) tt = 0; else if (tt > ONE) tt = ONE;
            int sr = SKYB_R + (((SKYT_R - SKYB_R) * tt) >> 16);
            int sg = SKYB_G + (((SKYT_G - SKYB_G) * tt) >> 16);
            int sb = SKYB_B + (((SKYT_B - SKYB_B) * tt) >> 16);

            for (int ix = 0; ix < rw; ix++) {
                int xd = ax[ix] + rayK1 + ayy;
                int zd = zdBase - bx[ix];

                // ---- DDA setup ----
                int px = pcx, py = pcy, pz = pcz;
                int adx = xd < 0 ? -xd : xd;
                int ady = yd < 0 ? -yd : yd;
                int adz = zd < 0 ? -zd : zd;
                int tdx = adx < MINDIR ? BIGT : (1 << 24) / adx;
                int tdy = ady < MINDIR ? BIGT : (1 << 24) / ady;
                int tdz = adz < MINDIR ? BIGT : (1 << 24) / adz;
                int sx, sy, sz, tmx, tmy, tmz;
                if (xd > 0)      { sx = 1;  tmx = ((px + 1) << FIX) - camXf; }
                else if (xd < 0) { sx = -1; tmx = camXf - (px << FIX); }
                else             { sx = 0;  tmx = 0; }
                if (yd > 0)      { sy = 1;  tmy = ((py + 1) << FIX) - camYf; }
                else if (yd < 0) { sy = -1; tmy = camYf - (py << FIX); }
                else             { sy = 0;  tmy = 0; }
                if (zd > 0)      { sz = 1;  tmz = ((pz + 1) << FIX) - camZf; }
                else if (zd < 0) { sz = -1; tmz = camZf - (pz << FIX); }
                else             { sz = 0;  tmz = 0; }
                tmx = tdx >= BIGT ? BIGT : ((tmx >> 8) * tdx >> 8);
                tmy = tdy >= BIGT ? BIGT : ((tmy >> 8) * tdy >> 8);
                tmz = tdz >= BIGT ? BIGT : ((tmz >> 8) * tdz >> 8);

                int col = 0, hitd = 0, hx = -1, hy = -1, hz = -1, hu = 0, hv = 0, thit = 0;
                int idx = mi(px, py, pz);
                int dIdxY = sy * PSY, dIdxZ = sz * PSZ;

                for (int step = 0; step < 64; step++) {
                    int tmin, axis;
                    if (tmx < tmy) { tmin = tmx; axis = 0; }
                    else           { tmin = tmy; axis = 1; }
                    if (tmz < tmin) { tmin = tmz; axis = 2; }
                    if (tmin > VIEW8) break;

                    if (axis == 0)      { px += sx; tmx += tdx; idx += sx;    }
                    else if (axis == 1) { py += sy; tmy += tdy; idx += dIdxY; }
                    else                { pz += sz; tmz += tdz; idx += dIdxZ; }

                    int tex = map[idx];
                    if (tex == OUTSIDE) break;
                    if (tex > 0) {
                        int u, v;
                        if (axis == 1) {
                            u = ((camXf + ((xd * tmin) >> 8)) >> 12) & 15;
                            v = ((camZf + ((zd * tmin) >> 8)) >> 12) & 15;
                            if (ydNeg) v += 32;
                        } else {
                            int hxf = camXf + ((xd * tmin) >> 8);
                            int hzf = camZf + ((zd * tmin) >> 8);
                            u = ((hxf + hzf) >> 12) & 15;
                            v = ((camYf + ((yd * tmin) >> 8)) >> 12) & 15;
                            v += 16;
                        }
                        int cc = texmap[u + (v << 4) + (tex << 10)];
                        if (cc != 0) {
                            col = cc;
                            hitd = axis;
                            thit = tmin;
                            hx = px; hy = py; hz = pz;
                            hu = u; hv = v & 15;
                            break;
                        }
                    }
                }

                int r, g, b;
                if (col == 0) {
                    r = sr; g = sg; b = sb;
                } else {
                    int fog = thit << 3;      // (thit<<16)/VIEW8, VIEW8 == 1<<13
                    if (fog > ONE) fog = ONE;
                    int sh = hitd * 256;      // pre-shaded row for this face
                    r = SHADE[sh + ((col >> 16) & 255)];
                    g = SHADE[sh + ((col >> 8) & 255)];
                    b = SHADE[sh + (col & 255)];
                    r = (r * (ONE - fog) + sr * fog) >> 16;
                    g = (g * (ONE - fog) + sg * fog) >> 16;
                    b = (b * (ONE - fog) + sb * fog) >> 16;

                    if (tx >= 0 && hx == tx && hy == ty && hz == tz) {
                        if (hu == 0 || hu == 15 || hv == 0 || hv == 15) {
                            r = 255; g = 255; b = 230;
                        } else {
                            r += (255 - r) * 18 / 100;
                            g += (255 - g) * 18 / 100;
                            b += (255 - b) * 18 / 100;
                        }
                    }
                }

                int c = (r << 16) | (g << 8) | b;
                int base = rowOut + ix * scale;
                for (int qy = 0; qy < scale; qy++) {
                    int p = base + qy * GAME;
                    for (int qx = 0; qx < scale; qx++) {
                        fb[p + qx] = c;
                    }
                }
            }
        }
        renderCount++;      // volatile write publishes the buffer to the UI thread
    }

    // ==================================================================
    // Paint
    // ==================================================================
    protected void paint(Graphics g) {
        ensureSize();
        if (!sized) return;

        switch (state) {
            case ST_PLAY:
                paintGame(g);
                break;
            case ST_PAUSE:
                paintGame(g);
                drawPause(g);
                break;
            case ST_MENU:
                drawMainMenu(g);
                break;
            case ST_HELP:
                drawHelp(g);
                break;
            case ST_LEVELS:
                drawLevels(g);
                break;
            default:
                break;
        }
    }

    private void paintGame(Graphics g) {
        // The UI is only refreshed when it changes: the top band is static, the
        // bottom band is rebuilt only when the selection changes.
        if (uiDirty) {
            buildBandBot();
            uiDirty = false;
        }

        // Volatile read: publishes the framebuffer pixels written by the game
        // thread's render() to this (UI) thread before they are blitted.
        renderStamp = renderCount;

        if (bandTop != null) g.drawImage(bandTop, 0, 0, Graphics.TOP | Graphics.LEFT);
        if (bandBot != null) g.drawImage(bandBot, 0, gameY + GAME, Graphics.TOP | Graphics.LEFT);

        // Any uncovered side strips (only if the screen is wider than GAME).
        if (gameX > 0) {
            g.setColor(BAND_DARK);
            g.fillRect(0, gameY, gameX, GAME);
            g.fillRect(gameX + GAME, gameY, screenW - gameX - GAME, GAME);
        }

        g.drawRGB(buf, 0, GAME, gameX, gameY, GAME, GAME, false);
        drawOverlay(g);
    }

    /** Live overlay drawn on top of the game square and the top band. */
    private void drawOverlay(Graphics g) {
        int cx = gameX + GAME / 2, cy = gameY + GAME / 2;
        g.setColor(0x000000);
        g.drawLine(cx - 6, cy, cx + 6, cy);
        g.drawLine(cx, cy - 6, cx, cy + 6);
        g.setColor(0xFFFFFF);
        g.drawLine(cx - 5, cy, cx + 5, cy);
        g.drawLine(cx, cy - 5, cx, cy + 5);

        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(font);
        g.setColor(0x000000);
        g.drawString("FPS " + fps + " d" + scale, 3, 2, Graphics.TOP | Graphics.LEFT);
    }

    // ==================================================================
    // Input (Sony Ericsson K800 key codes / standard MIDP game keys)
    // ==================================================================
    /**
     * Translates a raw key code to a standard Canvas game action, tolerating
     * implementations whose getGameAction() throws for unknown codes.
     */
    private int gameAction(int keyCode) {
        try {
            return getGameAction(keyCode);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Maps a raw joystick key code (-1..-5) to the matching Canvas action. */
    private static int joyAction(int keyCode) {
        switch (keyCode) {
            case JOY_UP:    return Canvas.UP;
            case JOY_DOWN:  return Canvas.DOWN;
            case JOY_LEFT:  return Canvas.LEFT;
            case JOY_RIGHT: return Canvas.RIGHT;
            case JOY_FIRE:  return Canvas.FIRE;
            default:        return 0;
        }
    }

    /** '*' on the SE / WTK keypad (confirmed by probing the emulator). */
    static final int KEY_STAR_RAW = 42;

    protected void keyPressed(int keyCode) {
        int action = joyAction(keyCode);
        if (action == 0) action = gameAction(keyCode);
        switch (state) {
            case ST_MENU:   keyMainMenu(action, keyCode); break;
            case ST_HELP:   keyHelp();                    break;
            case ST_LEVELS: keyLevels(action, keyCode);   break;
            case ST_PAUSE:  keyPause(action, keyCode);    break;
            case ST_PLAY:   keyPlay(action, keyCode);     break;
            default: break;
        }
    }

    protected void keyReleased(int keyCode) {
        int action = joyAction(keyCode);
        if (action == 0) action = gameAction(keyCode);
        switch (action) {
            case Canvas.UP:    kLookU = false; break;
            case Canvas.DOWN:  kLookD = false; break;
            case Canvas.LEFT:  kLookL = false; break;
            case Canvas.RIGHT: kLookR = false; break;
            default: break;
        }
        switch (keyCode) {
            case Canvas.KEY_NUM2: kFwd = false;   break;
            case Canvas.KEY_NUM8: kBack = false;  break;
            case Canvas.KEY_NUM4: kLeft = false;  break;
            case Canvas.KEY_NUM6: kRight = false; break;
            case Canvas.KEY_NUM5: kJump = false;  break;
            default: break;
        }
    }

    // ==================================================================
    // Per-screen key handling
    // ==================================================================
    private void keyMainMenu(int action, int keyCode) {
        if (action == Canvas.UP) {
            menuSel = (menuSel + MENU_ITEMS.length - 1) % MENU_ITEMS.length;
        } else if (action == Canvas.DOWN) {
            menuSel = (menuSel + 1) % MENU_ITEMS.length;
        } else if (action == Canvas.FIRE) {
            if (menuSel == 0) {
                levelSel = 0; trashFocus = false; confirmDelete = false;
                state = ST_LEVELS;
            } else if (menuSel == 1) {
                helpReturn = ST_MENU; state = ST_HELP;
            } else {
                midlet.exitGame();
            }
        }
    }

    private void keyHelp() {
        state = helpReturn;
    }

    private void keyLevels(int action, int keyCode) {
        if (confirmDelete) {
            if (action == Canvas.FIRE) deleteLevel(levelSel);
            confirmDelete = false;
            return;
        }
        if (keyCode == SOFT1) { state = ST_MENU; return; }
        if (keyCode == SOFT2) { if (levelExists[levelSel]) trashFocus = !trashFocus; return; }
        if (action == Canvas.UP) {
            levelSel = (levelSel + maxSlots - 1) % maxSlots; trashFocus = false;
        } else if (action == Canvas.DOWN) {
            levelSel = (levelSel + 1) % maxSlots; trashFocus = false;
        } else if (action == Canvas.RIGHT) {
            if (levelExists[levelSel]) trashFocus = true;
        } else if (action == Canvas.LEFT) {
            trashFocus = false;
        } else if (action == Canvas.FIRE) {
            if (trashFocus) confirmDelete = true;
            else enterLevel(levelSel);
        }
    }

    private void keyPause(int action, int keyCode) {
        if (keyCode == SOFT1 || keyCode == SOFT2) { state = ST_PLAY; return; }
        if (action == Canvas.UP) {
            pauseSel = (pauseSel + PAUSE_ITEMS.length - 1) % PAUSE_ITEMS.length;
        } else if (action == Canvas.DOWN) {
            pauseSel = (pauseSel + 1) % PAUSE_ITEMS.length;
        } else if (action == Canvas.FIRE) {
            pauseAction();
        }
    }

    private void pauseAction() {
        switch (pauseSel) {
            case 0: state = ST_PLAY; break;
            case 1: saveWorld(curLevel); break;
            case 2: if (loadWorld(curLevel)) { clearKeys(); state = ST_PLAY; } break;
            case 3: cycleDetail(); break;
            case 4: state = ST_MENU; menuSel = 0; break;
            default: break;
        }
    }

    private void keyPlay(int action, int keyCode) {
        if (keyCode == KEY_STAR_RAW) { pause(); return; }      // '*' = pause
        switch (keyCode) {
            case Canvas.KEY_NUM2: kFwd = true;   return;       // W
            case Canvas.KEY_NUM8: kBack = true;  return;       // S
            case Canvas.KEY_NUM4: kLeft = true;  return;       // A
            case Canvas.KEY_NUM6: kRight = true; return;       // D
            case Canvas.KEY_NUM5: kJump = true;  return;       // jump
            case Canvas.KEY_NUM1: prevBlock();   return;       // previous block
            case Canvas.KEY_NUM3: nextBlock();   return;       // next block
            case Canvas.KEY_NUM7: cycleDetail(); return;       // detail 2/3/4
            default: break;
        }
        if (keyCode == SOFT1) { breakBlock(); return; }
        if (keyCode == SOFT2) { placeBlock(); return; }
        switch (action) {
            case Canvas.UP:    kLookU = true;    break;
            case Canvas.DOWN:  kLookD = true;    break;
            case Canvas.LEFT:  kLookL = true;    break;
            case Canvas.RIGHT: kLookR = true;    break;
            case Canvas.FIRE:  centerHelper();   break;
            default: break;
        }
    }

    private void pause() {
        clearKeys();
        pauseSel = 0;
        state = ST_PAUSE;
    }

    private void clearKeys() {
        kFwd = kBack = kLeft = kRight = kJump = false;
        kLookU = kLookD = kLookL = kLookR = false;
    }

    private void enterLevel(int level) {
        curLevel = level;
        if (levelExists[level] && loadWorld(level)) {
            // resumed from storage
        } else {
            generateWorld(level);
            levelExists[level] = false;    // freshly generated, not yet saved
        }
        clearKeys();
        state = ST_PLAY;
    }

    // ==================================================================
    // Menu rendering
    // ==================================================================
    private void drawBackground(Graphics g) {
        if (bgImage != null) {
            g.drawImage(bgImage, 0, 0, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(BAND_DARK);
            g.fillRect(0, 0, screenW, screenH);
        }
    }

    /** Flat dark panel with a light border, for dialogs and help text. */
    private void drawPanel(Graphics g, int x, int y, int w, int h) {
        g.setColor(0x202020);
        g.fillRect(x, y, w, h);
        g.setColor(0x8A8A8A);
        g.drawRect(x, y, w, h);
        g.setColor(0x4A4A4A);
        g.drawRect(x + 1, y + 1, w - 2, h - 2);
    }

    /** Stone-textured button with a bevel; a white outline marks selection. */
    private void drawStoneButton(Graphics g, int x, int y, int w, int h, boolean sel) {
        if (sel) {
            g.setColor(0xFFFFFF);
            g.fillRect(x - 3, y - 3, w + 6, h + 6);
        }
        g.setClip(x, y, w, h);
        g.setColor(0x5A5A5A);
        g.fillRect(x, y, w, h);
        Image stone = thumb[4];
        if (stone != null) {
            for (int yy = y; yy < y + h; yy += 16)
                for (int xx = x; xx < x + w; xx += 16)
                    g.drawImage(stone, xx, yy, Graphics.TOP | Graphics.LEFT);
        }
        g.setClip(0, 0, screenW, screenH);
        g.setColor(0xD8D8D8);
        g.drawLine(x, y, x + w - 1, y);
        g.drawLine(x, y, x, y + h - 1);
        g.setColor(0x303030);
        g.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
        g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);
    }

    /**
     * Top-anchor y that vertically centres the current font about cy.  The WTK
     * Graphics implementation rejects Graphics.VCENTER (throws
     * IllegalArgumentException), so vertical centring is done by hand.
     */
    private int vtext(Graphics g, int cy) {
        return cy - g.getFont().getHeight() / 2;
    }

    /** Centred, drop-shadowed label. */
    private void label(Graphics g, String s, int cx, int cy) {
        int ty = vtext(g, cy);
        g.setColor(0x000000);
        g.drawString(s, cx + 1, ty + 1, Graphics.HCENTER | Graphics.TOP);
        g.setColor(0xFFFFFF);
        g.drawString(s, cx, ty, Graphics.HCENTER | Graphics.TOP);
    }

    /** Wooden title sign carrying the game name. */
    private void drawSign(Graphics g, int x, int y, int w, int h) {
        g.setClip(x, y, w, h);
        g.setColor(0x6B4A2B);
        g.fillRect(x, y, w, h);
        Image wood = thumb[7];
        if (wood != null) {
            for (int yy = y; yy < y + h; yy += 16)
                for (int xx = x; xx < x + w; xx += 16)
                    g.drawImage(wood, xx, yy, Graphics.TOP | Graphics.LEFT);
        }
        g.setClip(0, 0, screenW, screenH);
        g.setColor(0x3A2715);
        g.drawRect(x, y, w, h);
        g.drawRect(x + 1, y + 1, w - 2, h - 2);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
        g.setFont(f);
        int ty = vtext(g, y + h / 2);
        g.setColor(0x2B1B0E);
        g.drawString(MENU_TITLE, x + w / 2 + 2, ty + 2, Graphics.HCENTER | Graphics.TOP);
        g.setColor(0xF7E7B0);
        g.drawString(MENU_TITLE, x + w / 2, ty, Graphics.HCENTER | Graphics.TOP);
    }

    private void drawMainMenu(Graphics g) {
        drawBackground(g);
        int sw = screenW - 48, sh = 48, sx = 24;
        drawSign(g, sx, 24, sw, sh);
        Font bf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        g.setFont(bf);
        int bw = screenW - 64, bh = 32, bx = (screenW - bw) / 2;
        int by = 24 + sh + 22;
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            int y = by + i * (bh + 12);
            drawStoneButton(g, bx, y, bw, bh, i == menuSel);
            label(g, MENU_ITEMS[i], bx + bw / 2, y + bh / 2);
        }
        Font sf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(sf);
        g.setColor(0xE0E0E0);
        g.drawString("joystick: choose   fire: select", screenW / 2, screenH - 5,
                Graphics.HCENTER | Graphics.BOTTOM);
    }

    private void drawHelp(Graphics g) {
        drawBackground(g);
        int x = 10, y = 18, w = screenW - 20, h = screenH - 40;
        drawPanel(g, x, y, w, h);
        Font tf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        g.setFont(tf);
        g.setColor(0xFFFFFF);
        g.drawString("Help / Controls", x + w / 2, y + 8, Graphics.HCENTER | Graphics.TOP);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(f);
        String[] lines = {
            "Walk ......... 2 / 8 / 4 / 6",
            "Look ......... joystick",
            "Jump ......... 5",
            "Break block .. left soft key",
            "Place block .. right soft key",
            "Next / prev .. 3 / 1",
            "Act (centre) . joystick fire",
            "Pause ........ *",
            "Detail 2/3/4 . 7",
            "",
            "Press any key to go back"
        };
        int ly = y + 30;
        for (int i = 0; i < lines.length; i++) {
            g.setColor(0xE8E8E8);
            g.drawString(lines[i], x + 8, ly, Graphics.TOP | Graphics.LEFT);
            ly += 15;
        }
    }

    private void drawLevels(Graphics g) {
        drawBackground(g);
        Font tf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        g.setFont(tf);
        g.setColor(0xFFFFFF);
        g.drawString("Select a level", screenW / 2, 14, Graphics.HCENTER | Graphics.TOP);

        int rowH = 46, gap = 12, top = 48;
        int trashW = 30;
        int bw = screenW - 24 - trashW - 12;
        int bx = 12;
        Font lf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        Font sf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        for (int i = 0; i < maxSlots; i++) {
            int y = top + i * (rowH + gap);
            boolean sel = (i == levelSel) && !trashFocus;
            drawStoneButton(g, bx, y, bw, rowH, sel);
            g.setFont(lf);
            String name = levelExists[i] ? ("Level " + (i + 1)) : ("New world " + (i + 1));
            label(g, name, bx + bw / 2, y + rowH / 2 - 6);
            g.setFont(sf);
            g.setColor(levelExists[i] ? 0xD0D0D0 : 0xA8D8A8);
            g.drawString(levelExists[i] ? "saved" : "empty",
                    bx + bw / 2, vtext(g, y + rowH / 2 + 9), Graphics.HCENTER | Graphics.TOP);
            int tX = bx + bw + 6, tY = y + (rowH - 22) / 2;
            drawTrash(g, tX, tY, trashFocus && i == levelSel, levelExists[i]);
        }
        g.setFont(sf);
        g.setColor(0xE0E0E0);
        g.drawString("fire: play   right: delete   left: back", screenW / 2, screenH - 5,
                Graphics.HCENTER | Graphics.BOTTOM);

        if (confirmDelete) {
            drawDialog(g, "Delete Level " + (levelSel + 1) + "?",
                    "fire = yes, any other = no");
        }
    }

    private void drawDialog(Graphics g, String title, String sub) {
        int w = screenW - 40, h = 72;
        int x = (screenW - w) / 2, y = (screenH - h) / 2;
        drawPanel(g, x, y, w, h);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        g.setFont(f);
        g.setColor(0xFFFFFF);
        g.drawString(title, x + w / 2, vtext(g, y + h / 2 - 10), Graphics.HCENTER | Graphics.TOP);
        Font sf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(sf);
        g.setColor(0xD8D8D8);
        g.drawString(sub, x + w / 2, vtext(g, y + h / 2 + 14), Graphics.HCENTER | Graphics.TOP);
    }

    /** Small bin icon; grey when the slot is empty, white-outlined when focused. */
    private void drawTrash(Graphics g, int x, int y, boolean focused, boolean enabled) {
        int body = enabled ? 0xC0392B : 0x555555;
        int lid = enabled ? 0xE74C3C : 0x6A6A6A;
        g.setColor(0x000000);
        g.fillRect(x + 1, y + 4, 18, 17);
        g.setColor(body);
        g.fillRect(x + 2, y + 5, 16, 15);
        g.setColor(0x000000);
        g.fillRect(x + 1, y, 18, 4);
        g.setColor(lid);
        g.fillRect(x + 2, y + 1, 16, 2);
        g.fillRect(x + 7, y - 2, 6, 3);
        if (focused) {
            g.setColor(0xFFFFFF);
            g.drawRect(x - 3, y - 5, 25, 29);
        }
    }

    private void drawPause(Graphics g) {
        // Fake translucency: black scanlines over the frozen world frame.
        g.setColor(0x000000);
        for (int yy = gameY; yy < gameY + GAME; yy += 2) {
            g.drawLine(0, yy, screenW - 1, yy);
        }
        int w = screenW - 40, h = 32;
        int x = (screenW - w) / 2;
        int total = PAUSE_ITEMS.length * (h + 8);
        int y0 = (screenH - total) / 2 + 6;
        drawPanel(g, x - 8, y0 - 30, w + 16, total + 40);
        Font bf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        g.setFont(bf);
        g.setColor(0xFFFFFF);
        g.drawString("Paused", screenW / 2, vtext(g, y0 - 16), Graphics.HCENTER | Graphics.TOP);
        for (int i = 0; i < PAUSE_ITEMS.length; i++) {
            int y = y0 + i * (h + 8);
            drawStoneButton(g, x, y, w, h, i == pauseSel);
            String s = PAUSE_ITEMS[i];
            if (i == 3) s = "Graphics: " + GRAPH_NAMES[graphNameIndex()];
            label(g, s, x + w / 2, y + h / 2);
        }
    }

    private int graphNameIndex() {
        int i = scale - 2;
        if (i < 0) i = 0;
        if (i > 2) i = 2;
        return i;
    }

    // ==================================================================
    // Save storage (RecordStore): one record holding all level slots
    //   [0] version   [1] slot count   [2..2+n) exists flags
    //   then n * WORLD3 world bytes, then n * 6 big-endian floats
    // ==================================================================
    private static int blobSize(int slots) {
        return 2 + slots * (1 + LEVEL_BYTES);
    }

    /** Reads the save blob (record 1) or null if there is none. */
    private byte[] readBlob() {
        // The whole save lives in record id 1; if the store is empty (or the
        // record is missing) there is nothing to read.
        try {
            RecordStore rs = RecordStore.openRecordStore(RS_NAME, false);
            byte[] b = null;
            if (rs.getNumRecords() > 0) {
                try { b = rs.getRecord(1); } catch (Throwable e) { b = null; }
            }
            rs.closeRecordStore();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean writeBlob(byte[] b) {
        try {
            RecordStore rs = RecordStore.openRecordStore(RS_NAME, true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Works out how many level slots this handset's RMS can hold.  If a save
     * already exists its slot count is authoritative (it did fit once);
     * otherwise the capacity is probed with a throwaway record.
     */
    private void detectStorage() {
        for (int i = 0; i < MAX_LEVELS; i++) levelExists[i] = false;
        maxSlots = MAX_LEVELS;
        byte[] old = readBlob();
        if (old != null && old.length >= 2) {
            int n = old[1] & 0xff;
            if (n < 1) n = 1;
            if (n > MAX_LEVELS) n = MAX_LEVELS;
            maxSlots = n;
            for (int i = 0; i < n && (2 + i) < old.length; i++)
                levelExists[i] = (old[2 + i] & 0xff) != 0;
            return;
        }
        for (int n = MAX_LEVELS; n >= 1; n--) {
            if (probeCapacity(n)) { maxSlots = n; break; }
            maxSlots = 1;
        }
    }

    /**
     * Capacity probe.  Uses a separate throwaway store: record ids are never
     * reused, so probing the real store would burn id 1 and leave the save on
     * a later id (which readBlob/writeBlob could not find).
     */
    private boolean probeCapacity(int slots) {
        String probe = RS_NAME + "P";
        try {
            try { RecordStore.deleteRecordStore(probe); } catch (Throwable e) { }
            RecordStore rs = RecordStore.openRecordStore(probe, true);
            byte[] d = new byte[blobSize(slots)];
            int id = rs.addRecord(d, 0, d.length);
            rs.deleteRecord(id);
            rs.closeRecordStore();
            try { RecordStore.deleteRecordStore(probe); } catch (Throwable e) { }
            return true;
        } catch (Throwable t) {
            try { RecordStore.deleteRecordStore(probe); } catch (Throwable e) { }
            return false;
        }
    }

    /** Serialises the current world + player into slot {@code level}. */
    private boolean saveWorld(int level) {
        int n = maxSlots;
        if (level < 0 || level >= n) return false;
        byte[] blob = readBlob();
        if (blob == null || blob.length != blobSize(n)) {
            blob = new byte[blobSize(n)];
            blob[0] = 1;
            blob[1] = (byte) n;
        }
        blob[2 + level] = 1;
        int wo = 2 + n + level * WORLD3;
        for (int y = 0; y < WORLD; y++)
            for (int z = 0; z < WORLD; z++)
                for (int x = 0; x < WORLD; x++)
                    blob[wo++] = map[mi(x, y, z)];
        int fo = 2 + n + n * WORLD3 + level * 24;
        putF(blob, fo,      ox);
        putF(blob, fo + 4,  oy);
        putF(blob, fo + 8,  oz);
        putF(blob, fo + 12, yaw);
        putF(blob, fo + 16, pitch);
        putF(blob, fo + 20, sel);
        boolean ok = writeBlob(blob);
        if (ok) levelExists[level] = true;
        return ok;
    }

    /** Restores slot {@code level} into the live world + camera. */
    private boolean loadWorld(int level) {
        byte[] blob = readBlob();
        if (blob == null || blob.length < 2) return false;
        int n = blob[1] & 0xff;
        if (n < 1) n = 1;
        if (level < 0 || level >= n) return false;
        if ((blob[2 + level] & 0xff) == 0) return false;
        if (blob.length < blobSize(n)) return false;
        for (int i = 0; i < PLEN; i++) map[i] = OUTSIDE;
        int wo = 2 + n + level * WORLD3;
        for (int y = 0; y < WORLD; y++)
            for (int z = 0; z < WORLD; z++)
                for (int x = 0; x < WORLD; x++)
                    map[mi(x, y, z)] = blob[wo++];
        int fo = 2 + n + n * WORLD3 + level * 24;
        ox = getF(blob, fo);
        oy = getF(blob, fo + 4);
        oz = getF(blob, fo + 8);
        yaw = getF(blob, fo + 12);
        pitch = getF(blob, fo + 16);
        int s = (int) getF(blob, fo + 20);
        if (s >= 0 && s < SLOTS.length) sel = s;
        vy = 0f;
        onGround = false;
        uiDirty = true;
        return true;
    }

    private void deleteLevel(int level) {
        levelExists[level] = false;
        byte[] blob = readBlob();
        if (blob != null && (2 + level) < blob.length) {
            blob[2 + level] = 0;
            writeBlob(blob);
        }
    }

    private static void putF(byte[] b, int o, float f) {
        int bits = Float.floatToIntBits(f);
        b[o]     = (byte) (bits >>> 24);
        b[o + 1] = (byte) (bits >>> 16);
        b[o + 2] = (byte) (bits >>> 8);
        b[o + 3] = (byte) bits;
    }

    private static float getF(byte[] b, int o) {
        int bits = ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                 | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
        return Float.intBitsToFloat(bits);
    }
}
