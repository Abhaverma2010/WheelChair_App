package com.wheelchair.bluetoothswitch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Manages a Bluetooth Classic SPP (Serial Port Profile) connection to the
 * ESP32 wheelchair controller. All Bluetooth I/O happens on background
 * threads; callbacks to {@link ConnectionListener} are always delivered on
 * the main thread so the caller can update the UI directly.
 *
 * This class deliberately does NOT send any movement command on connect,
 * and it best-effort sends a single 'S' (stop) the moment a connection is
 * torn down or lost, before the socket is closed -- see sendStopBestEffort().
 *
 * The ESP32 firmware also sends text LINES back over the same connection
 * (see onDataReceived): "ALERT:FALL", "ALERT:CLEAR", "GPS:<lat>,<lon>",
 * and "GPS:NOFIX". This only reaches whichever phone is currently
 * Bluetooth-connected -- it is a local status channel, not a substitute
 * for the ESP32's independent SMS alert to a remote caregiver.
 */
public class BluetoothService {

    private static final String TAG = "BluetoothService";

    // Standard SPP UUID. This MUST match what the ESP32's BluetoothSerial
    // library advertises, which it does automatically for SerialPortProfile.
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface ConnectionListener {
        void onConnecting(String deviceName);
        void onConnected(String deviceName);
        void onConnectionFailed(String deviceName, String reason);
        void onDisconnected(String reason);
        /** Called for each text line the ESP32 sends back (ALERT:..., GPS:...). */
        void onDataReceived(String line);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private ConnectionListener listener;
    private volatile boolean isConnected = false;

    public void setListener(ConnectionListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return isConnected;
    }

    /** Starts an asynchronous connection attempt. Safe to call from the UI thread. */
    public synchronized void connect(BluetoothDevice device) {
        cancelConnectThread();
        cancelConnectedThread();

        final String deviceName = safeName(device);
        notifyConnecting(deviceName);

        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    /**
     * Tears the connection down from the app side (user tapped disconnect,
     * activity is closing, etc). Attempts a best-effort STOP command first.
     */
    public synchronized void disconnect(String reason) {
        sendStopBestEffort();
        cancelConnectThread();
        cancelConnectedThread();
        boolean wasConnected = isConnected;
        isConnected = false;
        if (wasConnected) {
            notifyDisconnected(reason);
        }
    }

    /** Sends a single-character command. Returns false immediately if not connected. */
    public boolean sendCommand(char command) {
        ConnectedThread thread;
        synchronized (this) {
            thread = connectedThread;
        }
        if (thread == null || !isConnected) {
            return false;
        }
        return thread.write((byte) command);
    }

    private synchronized void sendStopBestEffort() {
        if (connectedThread != null) {
            connectedThread.write((byte) 'S');
        }
    }

    public synchronized void shutdown() {
        sendStopBestEffort();
        cancelConnectThread();
        cancelConnectedThread();
        isConnected = false;
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException e) {
            // BLUETOOTH_CONNECT not granted -- fall back to the address, which
            // does not require that permission to read from a BluetoothDevice.
            return device.getAddress();
        }
    }

    private void notifyConnecting(String deviceName) {
        mainHandler.post(() -> {
            if (listener != null) listener.onConnecting(deviceName);
        });
    }

    private void notifyConnected(String deviceName) {
        isConnected = true;
        mainHandler.post(() -> {
            if (listener != null) listener.onConnected(deviceName);
        });
    }

    private void notifyConnectionFailed(String deviceName, String reason) {
        isConnected = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onConnectionFailed(deviceName, reason);
        });
    }

    private void notifyDisconnected(String reason) {
        isConnected = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onDisconnected(reason);
        });
    }

    private void notifyDataReceived(String line) {
        mainHandler.post(() -> {
            if (listener != null) listener.onDataReceived(line);
        });
    }

    private synchronized void cancelConnectThread() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
    }

    private synchronized void cancelConnectedThread() {
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    // ================= ConnectThread: performs the blocking connect() call =================
    private class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket socket;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            BluetoothSocket tmp;
            try {
                tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (SecurityException se) {
                notifyConnectionFailed(safeName(device), "Bluetooth permission not granted");
                return;
            } catch (IOException e) {
                notifyConnectionFailed(safeName(device), "Could not create a connection socket");
                return;
            }
            socket = tmp;

            // Discovery drastically slows down a connection attempt and should
            // always be cancelled first, per the Android Bluetooth guide.
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            try {
                if (adapter != null) {
                    adapter.cancelDiscovery();
                }
            } catch (SecurityException ignored) {
                // Already validated by the caller before this thread was started.
            }

            try {
                socket.connect();
            } catch (IOException connectException) {
                try {
                    socket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                notifyConnectionFailed(safeName(device),
                        "Timed out or the ESP32 refused the connection");
                return;
            } catch (SecurityException se) {
                notifyConnectionFailed(safeName(device), "Bluetooth permission not granted");
                return;
            }

            synchronized (BluetoothService.this) {
                connectThread = null;
                connectedThread = new ConnectedThread(socket);
                connectedThread.start();
            }
            notifyConnected(safeName(device));
        }

        void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket during cancel", e);
            }
        }
    }

    // ================= ConnectedThread: reads/writes once the socket is open =================
    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final BufferedReader reader;
        private final OutputStream outputStream;
        private volatile boolean running = true;

        ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            BufferedReader tmpReader = null;
            OutputStream tmpOut = null;
            try {
                tmpReader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Could not open Bluetooth streams", e);
            }
            reader = tmpReader;
            outputStream = tmpOut;
        }

        @Override
        public void run() {
            // The firmware sends text lines (ALERT:..., GPS:...) in addition
            // to not sending anything at all in between -- readLine() blocks
            // until a full line arrives OR the stream breaks, which is also
            // the most reliable way to detect an unexpected disconnection.
            while (running) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new IOException("End of stream");
                    }
                    line = line.trim();
                    if (!line.isEmpty()) {
                        notifyDataReceived(line);
                    }
                } catch (IOException e) {
                    if (running) {
                        running = false;
                        notifyDisconnected("Connection to the wheelchair was lost");
                    }
                    break;
                }
            }
        }

        /** Best-effort single-byte write. Never throws; returns success/failure. */
        synchronized boolean write(byte value) {
            if (outputStream == null) {
                return false;
            }
            try {
                outputStream.write(value);
                outputStream.flush();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Error writing command to output stream", e);
                if (running) {
                    running = false;
                    notifyDisconnected("Lost connection while sending a command");
                }
                return false;
            }
        }

        void cancel() {
            running = false;
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connected socket", e);
            }
        }
    }
}
