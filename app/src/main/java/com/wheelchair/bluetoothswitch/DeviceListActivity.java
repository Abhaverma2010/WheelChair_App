package com.wheelchair.bluetoothswitch;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lets the user pick a Bluetooth device to connect to. Shows already-paired
 * devices immediately (no special permission needed beyond BLUETOOTH_CONNECT
 * on Android 12+), and offers a "Scan" button to discover nearby devices
 * that have not been paired yet.
 *
 * Returns the chosen device's MAC address to the caller via
 * {@link #EXTRA_DEVICE_ADDRESS} through the normal activity result API.
 */
public class DeviceListActivity extends AppCompatActivity {

    private static final String TAG = "DeviceListActivity";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    private BluetoothAdapter bluetoothAdapter;
    private DeviceListAdapter pairedAdapter;
    private DeviceListAdapter availableAdapter;
    private TextView textEmptyState;
    private Button buttonScan;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    return;
                }
                if (!hasConnectPermission()) {
                    return;
                }
                try {
                    String name = device.getName();
                    if (name == null) {
                        name = "Unknown device";
                    }
                    availableAdapter.addIfAbsent(new DeviceListAdapter.Entry(
                            device, name, device.getAddress(), false));
                    updateEmptyState();
                } catch (SecurityException ignored) {
                    // Permission revoked mid-scan; simply skip this result.
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                buttonScan.setEnabled(true);
                buttonScan.setText(R.string.scan_for_devices);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_list);
        setTitle(R.string.select_device_title);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        ListView listPaired = findViewById(R.id.listPairedDevices);
        ListView listAvailable = findViewById(R.id.listAvailableDevices);
        textEmptyState = findViewById(R.id.textEmptyState);
        buttonScan = findViewById(R.id.buttonScan);
        TextView pairedHeader = findViewById(R.id.textPairedHeader);
        TextView availableHeader = findViewById(R.id.textAvailableHeader);

        pairedAdapter = new DeviceListAdapter(this);
        availableAdapter = new DeviceListAdapter(this);
        listPaired.setAdapter(pairedAdapter);
        listAvailable.setAdapter(availableAdapter);

        AdapterView.OnItemClickListener selectListener = (parent, view, position, id) -> {
            DeviceListAdapter adapter = (DeviceListAdapter) parent.getAdapter();
            DeviceListAdapter.Entry entry = adapter.getItem(position);
            Intent result = new Intent();
            result.putExtra(EXTRA_DEVICE_ADDRESS, entry.address);
            setResult(RESULT_OK, result);
            finish();
        };
        listPaired.setOnItemClickListener(selectListener);
        listAvailable.setOnItemClickListener(selectListener);

        buttonScan.setOnClickListener(v -> startScan());

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_no_bluetooth_adapter, Toast.LENGTH_LONG).show();
            pairedHeader.setVisibility(View.GONE);
            availableHeader.setVisibility(View.GONE);
            buttonScan.setEnabled(false);
            return;
        }

        loadPairedDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothAdapter != null && hasScanPermission()) {
            try {
                bluetoothAdapter.cancelDiscovery();
            } catch (SecurityException ignored) {
                // Nothing to do; the activity is closing anyway.
            }
        }
        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver);
            receiverRegistered = false;
        }
    }

    private void loadPairedDevices() {
        if (!hasConnectPermission()) {
            // MainActivity is expected to have already requested this before
            // launching this screen; this is a defensive fallback only.
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
            updateEmptyState();
            return;
        }
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            List<DeviceListAdapter.Entry> entries = new ArrayList<>();
            for (BluetoothDevice device : bonded) {
                String name = device.getName();
                if (name == null) {
                    name = "Unknown device";
                }
                entries.add(new DeviceListAdapter.Entry(device, name, device.getAddress(), true));
            }
            pairedAdapter.setEntries(entries);
        } catch (SecurityException e) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT while reading bonded devices", e);
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
        }
        updateEmptyState();
    }

    private void startScan() {
        if (!hasScanPermission()) {
            String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            ActivityCompat.requestPermissions(this, permissions, 100);
            return;
        }

        availableAdapter.clear();
        updateEmptyState();

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            ContextCompat.registerReceiver(this, discoveryReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }

        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            boolean started = bluetoothAdapter.startDiscovery();
            if (started) {
                buttonScan.setEnabled(false);
                buttonScan.setText(R.string.scanning);
            } else {
                Toast.makeText(this, "Could not start scanning", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            loadPairedDevices();
            startScan();
        } else {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void updateEmptyState() {
        boolean nothingToShow = pairedAdapter.getCount() == 0 && availableAdapter.getCount() == 0;
        textEmptyState.setVisibility(nothingToShow ? View.VISIBLE : View.GONE);
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // BLUETOOTH is a normal (install-time) permission pre-S.
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
