package com.wheelchair.bluetoothswitch;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Main wheelchair-control screen: Bluetooth Classic SPP connection to the
 * ESP32 ("ESP32_WHEELCHAIR"), the directional/stop D-pad, voice control,
 * and a debug/status panel.
 *
 * Safety invariants enforced throughout this file:
 *  - Movement buttons are disabled whenever not connected (default state).
 *  - No movement command is ever sent automatically on connect/startup.
 *  - Tapping a direction starts that movement and it continues until Stop
 *    (or a new direction) is tapped explicitly -- not hold-to-move.
 *  - Losing the connection or backgrounding the app still sends 'S' (stop)
 *    as a last-resort safety net, independent of the tap/hold behavior
 *    above: the wheelchair otherwise has no way to be stopped once control
 *    is lost.
 *  - Voice control never bypasses the connection check.
 */
public class MainActivity extends AppCompatActivity implements BluetoothService.ConnectionListener {

    private static final SimpleDateFormat LOG_TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private BluetoothAdapter bluetoothAdapter;
    private final BluetoothService bluetoothService = new BluetoothService();

    private TextView textStatus;
    private Button buttonSelectDevice;
    private Button buttonForward;
    private Button buttonBackward;
    private Button buttonLeft;
    private Button buttonRight;
    private Button buttonStop;
    private Button buttonVoice;
    private TextView textCurrentCommand;
    private TextView textBluetoothDevice;
    private TextView textDebugBluetooth;
    private TextView textDebugDevice;
    private TextView textDebugLastCommand;
    private TextView textDebugLastVoice;
    private ScrollView scrollLog;
    private TextView textLog;
    private View bannerFallAlert;
    private Button buttonDismissAlert;
    private TextView textGpsLocation;
    private Button buttonViewOnMap;

    private String connectedDeviceName = null;
    private String lastRecognizedVoice = null;
    private boolean receiverRegistered = false;
    private double lastGpsLat = 0;
    private double lastGpsLon = 0;
    private boolean hasGpsFix = false;

    // ===================== Activity Result launchers =====================

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted == null || !granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    ensureBluetoothEnabledThenOpenDeviceList();
                } else {
                    Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
                    appendLog("Bluetooth permission denied");
                }
            });

    private final ActivityResultLauncher<Intent> enableBtLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    openDeviceList();
                } else {
                    Toast.makeText(this, R.string.error_bluetooth_enable_declined, Toast.LENGTH_LONG).show();
                    appendLog("Bluetooth enable request declined");
                }
            });

    private final ActivityResultLauncher<Intent> deviceListLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    return;
                }
                String address = result.getData()
                        .getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                if (address == null) {
                    return;
                }
                connectToAddress(address);
            });

    private final ActivityResultLauncher<Intent> voiceRecognitionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::handleVoiceResult);

    // ===================== ACL disconnect receiver =====================
    // Extra safety net: fires as soon as the Android Bluetooth stack reports
    // the physical link dropped, which can be faster than waiting for a
    // socket read() to throw inside BluetoothService.
    private final BroadcastReceiver aclDisconnectReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())
                    && bluetoothService.isConnected()) {
                onDisconnected(getString(R.string.error_socket_lost));
            }
        }
    };

    // ===================== Lifecycle =====================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        bluetoothService.setListener(this);

        buttonSelectDevice.setOnClickListener(v -> onSelectDeviceClicked());
        buttonStop.setOnClickListener(v -> sendMovementCommand('S'));
        setupMovementButton(buttonForward, 'F');
        setupMovementButton(buttonBackward, 'B');
        setupMovementButton(buttonLeft, 'L');
        setupMovementButton(buttonRight, 'R');
        buttonVoice.setOnClickListener(v -> onVoiceControlClicked());
        buttonDismissAlert.setOnClickListener(v -> bannerFallAlert.setVisibility(View.GONE));
        buttonViewOnMap.setOnClickListener(v -> openGpsInMaps());

        // Default state on launch: Disconnected, controls disabled, and
        // critically, NO movement command is sent automatically here.
        setControlsEnabled(false);
        updateDebugPanel();
        appendLog("App started");

        if (bluetoothAdapter == null) {
            textStatus.setText(R.string.error_no_bluetooth_adapter);
            buttonSelectDevice.setEnabled(false);
            Toast.makeText(this, R.string.error_no_bluetooth_adapter, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            ContextCompat.registerReceiver(this, aclDisconnectReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Safety: if the screen goes into the background while still
        // connected, immediately stop the wheelchair. The connection itself
        // is left open so control resumes cleanly if the user comes back.
        if (bluetoothService.isConnected()) {
            boolean sent = bluetoothService.sendCommand('S');
            if (sent) {
                appendLog("App backgrounded -> Sent: S");
            }
        }
        if (receiverRegistered) {
            unregisterReceiver(aclDisconnectReceiver);
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bluetoothService.shutdown();
    }

    private void bindViews() {
        textStatus = findViewById(R.id.textStatus);
        buttonSelectDevice = findViewById(R.id.buttonSelectDevice);
        buttonForward = findViewById(R.id.buttonForward);
        buttonBackward = findViewById(R.id.buttonBackward);
        buttonLeft = findViewById(R.id.buttonLeft);
        buttonRight = findViewById(R.id.buttonRight);
        buttonStop = findViewById(R.id.buttonStop);
        buttonVoice = findViewById(R.id.buttonVoice);
        textCurrentCommand = findViewById(R.id.textCurrentCommand);
        textBluetoothDevice = findViewById(R.id.textBluetoothDevice);
        textDebugBluetooth = findViewById(R.id.textDebugBluetooth);
        textDebugDevice = findViewById(R.id.textDebugDevice);
        textDebugLastCommand = findViewById(R.id.textDebugLastCommand);
        textDebugLastVoice = findViewById(R.id.textDebugLastVoice);
        scrollLog = findViewById(R.id.scrollLog);
        textLog = findViewById(R.id.textLog);
        bannerFallAlert = findViewById(R.id.bannerFallAlert);
        buttonDismissAlert = findViewById(R.id.buttonDismissAlert);
        textGpsLocation = findViewById(R.id.textGpsLocation);
        buttonViewOnMap = findViewById(R.id.buttonViewOnMap);
    }

    // ===================== Select device / connect flow =====================

    private void onSelectDeviceClicked() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_no_bluetooth_adapter, Toast.LENGTH_LONG).show();
            return;
        }
        if (bluetoothService.isConnected()) {
            bluetoothService.disconnect("Switching devices");
        }
        checkPermissionsThenOpenDeviceList();
    }

    private void checkPermissionsThenOpenDeviceList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasScan || !hasConnect) {
                requestPermissionsLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                });
                return;
            }
        }
        // Pre-Android 12: BLUETOOTH/BLUETOOTH_ADMIN are normal (install-time)
        // permissions, so no runtime request is required just to connect to
        // an already-paired device.
        ensureBluetoothEnabledThenOpenDeviceList();
    }

    private void ensureBluetoothEnabledThenOpenDeviceList() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            try {
                enableBtLauncher.launch(enableIntent);
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
            }
            return;
        }
        openDeviceList();
    }

    private void openDeviceList() {
        deviceListLauncher.launch(new Intent(this, DeviceListActivity.class));
    }

    private void connectToAddress(String address) {
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            bluetoothService.connect(device);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Invalid Bluetooth address", Toast.LENGTH_SHORT).show();
        }
    }

    // ===================== BluetoothService.ConnectionListener =====================

    @Override
    public void onConnecting(String deviceName) {
        textStatus.setText(R.string.status_connecting);
        textStatus.setTextColor(ContextCompat.getColor(this, R.color.statusConnecting));
        appendLog("Connecting to " + deviceName + "…");
    }

    @Override
    public void onConnected(String deviceName) {
        connectedDeviceName = deviceName;
        textStatus.setText(getString(R.string.status_connected_to, deviceName));
        textStatus.setTextColor(ContextCompat.getColor(this, R.color.statusConnected));
        textBluetoothDevice.setText(deviceName);
        setControlsEnabled(true);
        updateDebugPanel();
        appendLog("Bluetooth connected: " + deviceName);
        // IMPORTANT: no movement command is sent here. Connecting never
        // implies "go".
    }

    @Override
    public void onConnectionFailed(String deviceName, String reason) {
        connectedDeviceName = null;
        textStatus.setText(R.string.status_connection_failed);
        textStatus.setTextColor(ContextCompat.getColor(this, R.color.statusDisconnected));
        setControlsEnabled(false);
        updateDebugPanel();
        resetAlertAndGpsUi();
        appendLog("Connection failed (" + deviceName + "): " + reason);
        Toast.makeText(this, getString(R.string.error_connect_failed, deviceName), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDisconnected(String reason) {
        connectedDeviceName = null;
        textStatus.setText(R.string.status_disconnected);
        textStatus.setTextColor(ContextCompat.getColor(this, R.color.statusDisconnected));
        textBluetoothDevice.setText(R.string.label_none);
        setControlsEnabled(false);
        updateDebugPanel();
        // Deliberately NOT clearing the fall alert banner here: if the
        // wheelchair just fell AND lost connection, that is exactly the
        // moment the banner matters most. GPS display is left as-is too,
        // since it was still accurate up to the moment of disconnection.
        appendLog("Disconnected: " + reason);
    }

    /**
     * Handles a text line sent back by the ESP32: "ALERT:FALL",
     * "ALERT:CLEAR", "GPS:<lat>,<lon>", or "GPS:NOFIX". Anything else is
     * logged and ignored rather than guessed at.
     */
    @Override
    public void onDataReceived(String line) {
        appendLog("Received: " + line);

        if (line.equals("ALERT:FALL")) {
            bannerFallAlert.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.alert_fall_title, Toast.LENGTH_LONG).show();
            return;
        }
        if (line.equals("ALERT:CLEAR")) {
            bannerFallAlert.setVisibility(View.GONE);
            return;
        }
        if (line.equals("GPS:NOFIX")) {
            hasGpsFix = false;
            textGpsLocation.setText(R.string.gps_no_fix);
            buttonViewOnMap.setEnabled(false);
            return;
        }
        if (line.startsWith("GPS:")) {
            String[] parts = line.substring(4).split(",");
            if (parts.length == 2) {
                try {
                    lastGpsLat = Double.parseDouble(parts[0].trim());
                    lastGpsLon = Double.parseDouble(parts[1].trim());
                    hasGpsFix = true;
                    textGpsLocation.setText(String.format(Locale.US, "%.6f, %.6f", lastGpsLat, lastGpsLon));
                    buttonViewOnMap.setEnabled(true);
                } catch (NumberFormatException e) {
                    appendLog("Malformed GPS line ignored: " + line);
                }
            }
        }
        // Anything else (unrecognized line) is already logged above and
        // otherwise ignored -- never guess at an unknown command.
    }

    private void resetAlertAndGpsUi() {
        bannerFallAlert.setVisibility(View.GONE);
        textGpsLocation.setText(R.string.label_none);
        buttonViewOnMap.setEnabled(false);
        hasGpsFix = false;
    }

    private void openGpsInMaps() {
        if (!hasGpsFix) {
            return;
        }
        Uri geoUri = Uri.parse(String.format(Locale.US,
                "geo:%f,%f?q=%f,%f(Wheelchair)", lastGpsLat, lastGpsLon, lastGpsLat, lastGpsLon));
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, geoUri);
        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            // No maps app installed -- fall back to a browser URL instead
            // of failing silently.
            Uri webUri = Uri.parse(String.format(Locale.US,
                    "https://maps.google.com/?q=%f,%f", lastGpsLat, lastGpsLon));
            startActivity(new Intent(Intent.ACTION_VIEW, webUri));
        }
    }

    // ===================== Movement controls =====================

    /**
     * Tapping a direction starts that movement; it keeps going until the
     * user taps Stop (or another direction, which simply sends a new
     * command) -- not hold-to-move. The connection-loss and app-backgrounded
     * auto-stops elsewhere in this class are a separate safety net (the
     * wheelchair otherwise has no way to be stopped once control is lost)
     * and are unaffected by this.
     */
    private void setupMovementButton(Button button, char command) {
        button.setOnClickListener(v -> sendMovementCommand(command));
    }

    /** Sends a single command character if (and only if) currently connected. */
    private void sendMovementCommand(char command) {
        if (!bluetoothService.isConnected()) {
            // Defensive guard; the buttons are disabled while disconnected,
            // so this path should not normally be reachable.
            return;
        }
        boolean sent = bluetoothService.sendCommand(command);
        String label = VoiceCommandMapper.commandLabel(command);
        if (sent) {
            textCurrentCommand.setText(label);
            textDebugLastCommand.setText(getString(R.string.debug_last_command) + " " + command);
            appendLog("Sent: " + command);
        } else {
            appendLog("Send failed: " + command);
        }
    }

    // ===================== Voice control =====================

    private void onVoiceControlClicked() {
        if (!bluetoothService.isConnected()) {
            // Voice control must never bypass the Bluetooth safety check.
            Toast.makeText(this, R.string.btn_voice_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.error_speech_unsupported, Toast.LENGTH_LONG).show();
            appendLog("Voice control unavailable on this device");
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt));
        try {
            voiceRecognitionLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_speech_unsupported, Toast.LENGTH_LONG).show();
            appendLog("Could not start voice recognition: " + e.getMessage());
        }
    }

    private void handleVoiceResult(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        ArrayList<String> matches = result.getData()
                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (matches == null || matches.isEmpty()) {
            Toast.makeText(this, R.string.error_speech_not_recognized, Toast.LENGTH_SHORT).show();
            return;
        }

        String recognizedText = matches.get(0);
        lastRecognizedVoice = recognizedText;
        updateDebugPanel();

        char command = VoiceCommandMapper.map(recognizedText);
        if (command == 0) {
            Toast.makeText(this, R.string.error_speech_not_recognized, Toast.LENGTH_SHORT).show();
            appendLog("Voice not recognized: \"" + recognizedText + "\"");
            return;
        }

        // Re-check connection state: voice recognition is an asynchronous,
        // possibly multi-second round trip, and the wheelchair may have
        // disconnected while the user was speaking.
        if (!bluetoothService.isConnected()) {
            Toast.makeText(this, R.string.btn_voice_locked, Toast.LENGTH_SHORT).show();
            appendLog("Voice command \"" + recognizedText + "\" ignored: not connected");
            return;
        }

        appendLog("Recognized: \"" + recognizedText + "\" -> " + command);
        sendMovementCommand(command);
    }

    // ===================== UI state helpers =====================

    private void setControlsEnabled(boolean enabled) {
        buttonForward.setEnabled(enabled);
        buttonBackward.setEnabled(enabled);
        buttonLeft.setEnabled(enabled);
        buttonRight.setEnabled(enabled);
        buttonStop.setEnabled(enabled);
        buttonVoice.setEnabled(enabled);
        buttonVoice.setText(enabled ? R.string.btn_voice : R.string.btn_voice_locked);
    }

    private void updateDebugPanel() {
        boolean connected = bluetoothService.isConnected();
        textDebugBluetooth.setText(getString(R.string.debug_bluetooth) + " "
                + (connected ? "Connected" : "Disconnected"));
        textDebugDevice.setText(getString(R.string.debug_device) + " "
                + (connectedDeviceName != null ? connectedDeviceName : getString(R.string.label_none)));
        textDebugLastVoice.setText(getString(R.string.debug_last_voice) + " "
                + (lastRecognizedVoice != null ? "\"" + lastRecognizedVoice + "\"" : getString(R.string.label_none)));
    }

    private void appendLog(String message) {
        String line = "[" + LOG_TIME_FORMAT.format(new Date()) + "] " + message + "\n";
        textLog.append(line);
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }
}
