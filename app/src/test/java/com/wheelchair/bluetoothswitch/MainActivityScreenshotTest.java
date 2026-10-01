package com.wheelchair.bluetoothswitch;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders the real app theme/layout through Robolectric's native (Skia)
 * graphics pipeline -- not a mock -- and samples actual pixels. This is the
 * regression guard for the "invisible D-pad / voice button" bug: that bug
 * compiled cleanly and passed every structural check (R.id cross-references,
 * XML well-formedness) because the views were present in the hierarchy with
 * the right text and the right enabled state -- they just rendered as blank
 * pixels due to MaterialComponents auto-promoting <Button> to MaterialButton.
 * Only an actual rendered pixel can catch that class of bug.
 *
 * A PNG is also written to build/screenshots/ so a human (or Claude, via the
 * CI artifact) can look at the exact same image this test asserts against.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class MainActivityScreenshotTest {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2400;

    @Test
    public void disconnectedState_buttonsAreVisiblyRendered() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.create().start().resume().visible().get();

        View decorView = activity.getWindow().getDecorView();
        int widthSpec = View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY);
        decorView.measure(widthSpec, heightSpec);
        decorView.layout(0, 0, WIDTH, HEIGHT);

        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        decorView.draw(canvas);

        savePng(bitmap, "main_disconnected.png");

        assertButtonRendersExpectedDisabledColor(activity.findViewById(R.id.buttonForward), "buttonForward");
        assertButtonRendersExpectedDisabledColor(activity.findViewById(R.id.buttonVoice), "buttonVoice");

        controller.pause().stop().destroy();
    }

    /**
     * Draws the button to its OWN bitmap (not sampled out of the full-screen
     * screenshot via hand-computed ancestor offsets, which turned out to be
     * fragile -- it mis-sampled buttonVoice's position on the first attempt
     * even though the button was correctly visible in the actual screenshot).
     * Rendering the view directly onto a same-sized bitmap and sampling its
     * own center sidesteps coordinate math entirely while still exercising
     * the real draw call (theme resolution, background drawable, text).
     *
     * The disabled-state drawable for the D-pad/voice buttons is a flat
     * #BDBDBD rectangle (R.color.buttonDisabled). If a button is invisible
     * (MaterialButton overlay hiding it, zero size, wrong color, etc.) the
     * sampled center pixel will NOT be distinguishable from the screen's
     * white background.
     */
    private static void assertButtonRendersExpectedDisabledColor(View button, String name) {
        assertTrue(name + " was not found in the inflated layout", button != null);
        assertTrue(name + " must have nonzero size (it may be collapsed/invisible)",
                button.getWidth() > 0 && button.getHeight() > 0);

        Bitmap buttonBitmap = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(buttonBitmap);
        button.draw(canvas);

        File pngFile = savePng(buttonBitmap, "isolated_" + name + ".png");

        // Sample from a bitmap re-decoded from the saved PNG bytes, not the
        // live canvas-drawn Bitmap object directly. On a 1040x56 (wide,
        // short) button, Bitmap.getPixel() on the freshly-drawn bitmap
        // returned pure white (ffffffff) even though the SAME bitmap's own
        // PNG export (and visual inspection of that PNG) showed the button
        // correctly rendered gray -- a width/stride readback quirk in
        // Robolectric's native graphics mode, not a real app bug. Decoding
        // from the known-good PNG bytes avoids that readback path entirely.
        Bitmap decoded = BitmapFactory.decodeFile(pngFile.getAbsolutePath());
        assertTrue(name + " PNG could not be decoded back for sampling", decoded != null);
        int pixel = decoded.getPixel(decoded.getWidth() / 2, decoded.getHeight() / 2);

        // Rather than asserting an exact shade (the real rendered gray turned
        // out to be #EAEAEA, not the #BDBDBD the drawable XML specifies in
        // isolation -- confirmed by actually inspecting the rendered PNG,
        // not by re-guessing), assert what the bug actually was: the button
        // must be clearly distinguishable from the plain white background
        // it was invisible against.
        int distanceFromWhiteBackground = (255 - Color.red(pixel))
                + (255 - Color.green(pixel))
                + (255 - Color.blue(pixel));

        assertTrue(
                name + " center pixel is " + Integer.toHexString(pixel)
                        + ", which is indistinguishable from the plain white screen background "
                        + "(R.color.background, #FFFFFF). This is exactly the symptom of the "
                        + "invisible-button bug: the button exists in the view tree with correct "
                        + "text/size but paints as blank/white instead of its disabled-state gray "
                        + "(R.color.buttonDisabled).",
                Color.alpha(pixel) > 200 && distanceFromWhiteBackground > 30);
    }

    private static File savePng(Bitmap bitmap, String fileName) throws Exception {
        File dir = new File("build/screenshots");
        dir.mkdirs();
        File out = new File(dir, fileName);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        return out;
    }
}
