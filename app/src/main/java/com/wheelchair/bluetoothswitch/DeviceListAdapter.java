package com.wheelchair.bluetoothswitch;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Backs the paired-devices and available-devices lists in
 * {@link DeviceListActivity}. Each row shows the device's name, MAC
 * address, and whether it is Paired or Available (just discovered).
 */
public class DeviceListAdapter extends BaseAdapter {

    /** Immutable holder for a single row's data. */
    public static final class Entry {
        public final BluetoothDevice device;
        public final String name;
        public final String address;
        public final boolean paired;

        public Entry(BluetoothDevice device, String name, String address, boolean paired) {
            this.device = device;
            this.name = name;
            this.address = address;
            this.paired = paired;
        }
    }

    private final Context context;
    private final List<Entry> entries = new ArrayList<>();

    public DeviceListAdapter(Context context) {
        this.context = context;
    }

    /** Replaces all entries and refreshes the list. */
    public void setEntries(List<Entry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    /** Adds one entry if its address is not already present, and refreshes. */
    public void addIfAbsent(Entry entry) {
        for (Entry existing : entries) {
            if (existing.address.equals(entry.address)) {
                return;
            }
        }
        entries.add(entry);
        notifyDataSetChanged();
    }

    public void clear() {
        entries.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public Entry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = LayoutInflater.from(context).inflate(R.layout.item_bluetooth_device, parent, false);
        }
        Entry entry = entries.get(position);

        TextView name = row.findViewById(R.id.textDeviceName);
        TextView address = row.findViewById(R.id.textDeviceAddress);
        TextView state = row.findViewById(R.id.textDeviceState);

        name.setText(entry.name);
        address.setText(entry.address);
        state.setText(entry.paired
                ? context.getString(R.string.paired)
                : context.getString(R.string.available));

        return row;
    }
}
