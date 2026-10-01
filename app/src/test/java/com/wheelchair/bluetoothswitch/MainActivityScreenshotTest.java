package com.wheelchair.bluetoothswitch;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewParent;

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

        View buttonForward = activity.findViewById(R.id.buttonForward);
        View buttonVoice = activity.findViewById(R.id.buttonVoice);

        assertButtonRendersExpectedDisabledColor(bitmap, decorView, buttonForward, "buttonForward");
        assertButtonRendersExpectedDisabledColor(bitmap, decorView, buttonVoice, "buttonVoice");

        controller.pause().stop().destroy();
    }

    /**
     * The disabled-state drawable for the D-pad/voice buttons is a flat
     * #BDBDBD rectangle (R.color.buttonDisabled). If a button is invisible
     * (MaterialButton overlay hiding it, zero size, wrong color, etc.) the
     * sampled center pixel will NOT be close to that color -- it will be
     * the screen's white background instead.
     */
    private static void assertButtonRendersExpectedDisabledColor(
            Bitmap bitmap, View decorView, View button, String name) {
        assertTrue(name + " was not found in the inflated layout", button != null);
        assertTrue(name + " must have nonzero size (it may be collapsed/invisible)",
                button.getWidth() > 0 && button.getHeight() > 0);

        int[] offset = locationRelativeTo(button, decorView);
        int sampleX = offset[0] + button.getWidth() / 2;
        int sampleY = offset[1] + button.getHeight() / 2;

        assertTrue(name + " sample point is outside the rendered bitmap bounds",
                sampleX >= 0 && sampleX < bitmap.getWidth() && sampleY >= 0 && sampleY < bitmap.getHeight());

        int pixel = bitmap.getPixel(sampleX, sampleY);
        int expected = 0xFFBDBDBD; // R.color.buttonDisabled

        assertTrue(
                name + " center pixel is " + Integer.toHexString(pixel)
                        + " but the disabled-state drawable (R.color.buttonDisabled, #BDBDBD) should render there. "
                        + "This is exactly the symptom of the MaterialButton-overlay bug: the button exists in the "
                        + "view tree with correct text/size but paints as blank/white.",
                colorsAreClose(pixel, expected, 24));
    }

    private static boolean colorsAreClose(int a, int b, int tolerancePerChannel) {
        return Math.abs(Color.red(a) - Color.red(b)) <= tolerancePerChannel
                && Math.abs(Color.green(a) - Color.green(b)) <= tolerancePerChannel
                && Math.abs(Color.blue(a) - Color.blue(b)) <= tolerancePerChannel
                && Color.alpha(a) > 200;
    }

    private static int[] locationRelativeTo(View view, View ancestor) {
        int x = 0;
        int y = 0;
        View current = view;
        while (current != null && current != ancestor) {
            x += current.getLeft();
            y += current.getTop();
            ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
        }
        return new int[]{x, y};
    }

    private static void savePng(Bitmap bitmap, String fileName) throws Exception {
        File dir = new File("build/screenshots");
        dir.mkdirs();
        File out = new File(dir, fileName);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
