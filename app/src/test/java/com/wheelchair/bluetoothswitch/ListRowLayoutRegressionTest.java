package com.wheelchair.bluetoothswitch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Guards against the exact bug that made the "Select Bluetooth Device" list
 * unresponsive: a ListView row root with android:clickable="true" and/or
 * android:focusable="true" makes AdapterView stop delivering taps to
 * OnItemClickListener, because the row claims to handle its own touch
 * input. The ripple background (?attr/selectableItemBackground) does not
 * need either attribute to work.
 */
public class ListRowLayoutRegressionTest {

    @Test
    public void itemBluetoothDeviceRow_isNotSelfClickable() throws Exception {
        File layoutFile = findLayoutFile("item_bluetooth_device.xml");
        Element root = parseRoot(layoutFile);

        String clickable = root.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "clickable");
        String focusable = root.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "focusable");

        assertFalse(
                "item_bluetooth_device.xml root must not be android:clickable=\"true\" "
                        + "(it eats ListView item clicks) -- found: " + clickable,
                "true".equals(clickable));
        assertFalse(
                "item_bluetooth_device.xml root must not be android:focusable=\"true\" "
                        + "(it eats ListView item clicks) -- found: " + focusable,
                "true".equals(focusable));
    }

    private static Element parseRoot(File layoutFile) throws Exception {
        assertTrue("Layout file not found: " + layoutFile.getAbsolutePath(), layoutFile.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(layoutFile);
        return doc.getDocumentElement();
    }

    /**
     * Unit tests run with the module directory as the working directory under
     * Gradle, but resolve defensively in case that ever changes.
     */
    private static File findLayoutFile(String name) {
        String[] candidates = {
                "src/main/res/layout/" + name,
                "app/src/main/res/layout/" + name,
        };
        for (String candidate : candidates) {
            File f = new File(candidate);
            if (f.isFile()) {
                return f;
            }
        }
        return new File(candidates[0]);
    }
}
