import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

/**
 * Minecraft4k - portable J2ME (CLDC 1.1 / MIDP 2.0) port of the
 * "Minecraft4k" JavaScript voxel raycaster (see src-original/).
 *
 * Target device class: Sony Ericsson K800 (240x320, MIDP 2.0).
 */
public class Minecraft4k extends MIDlet {

    private GameCanvas canvas;

    public void startApp() {
        if (canvas == null) {
            canvas = new GameCanvas(this);
        }
        Display.getDisplay(this).setCurrent(canvas);
        canvas.start();
    }

    public void pauseApp() {
        if (canvas != null) {
            canvas.stop();
        }
    }

    public void destroyApp(boolean unconditional) {
        if (canvas != null) {
            canvas.stop();
        }
    }

    /** Called by the game canvas when the player wants to quit. */
    void exitGame() {
        destroyApp(true);
        notifyDestroyed();
    }
}
