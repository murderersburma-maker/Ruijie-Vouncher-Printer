
package com.example.ruijievoucherprinter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.bluetooth.*;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import android.graphics.Color;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static class Profile {
        String name, period, quota, upload, download;

        Profile(String n, String p, String q, String u, String d) {
            name=n;
            period=p;
            quota=q;
            upload=u;
            download=d;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static class Voucher {
        String code, password;
        Profile profile;

        Voucher(String c, String pw, Profile pr) {
            code=c;
            password=pw;
            profile=pr;
        }
    }

    private static final int PICK_CSV=41, REQ_BT=42;

    private static final UUID SPP=UUID.fromString(
        "00001101-0000-1000-8000-00805F9B34FB"
    );

    private final ArrayList<Profile> profiles=new ArrayList<>();
    private final ArrayList<Voucher> vouchers=new ArrayList<>();
    private final HashSet<String> seen=new HashSet<>();
    private final ArrayList<BluetoothDevice> printerDevices=new ArrayList<>();

    private final ExecutorService worker=
        Executors.newSingleThreadExecutor();

    private LinearLayout voucherList;
    private TextView status;

    private Spinner printerSpinner, paperSpinner;
    private Spinner profileSpinner, alignmentSpinner;

    private EditText groupName, groupPeriod, groupQuota;
    private EditText groupUpload, groupDownload, codeInput;
    private EditText customWidth, leftMargin, rightMargin;

    private BluetoothAdapter bluetooth;
    private BluetoothSocket socket;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        bluetooth=BluetoothAdapter.getDefaultAdapter();

        seedProfiles();
        buildUi();
        refreshPrinters();
    }

    private void seedProfiles() {
        profiles.add(new Profile("1Month","30 days","","",""));
        profiles.add(new Profile("1Week","7 days","30GB","",""));
        profiles.add(new Profile("1Day","1 day","","",""));
        profiles.add(new Profile("5Hour","300 minutes","","",""));
        profiles.add(new Profile("3Hour","180 minutes","","",""));
        profiles.add(new Profile("2Hour","2 hours","4GB","",""));
        profiles.add(new Profile("1Hour","1 hour","","",""));
    }

    private TextView label(String s) {
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(Color.rgb(35,55,75));
        t.setTextSize(14);
        t.setPadding(0,7,0,3);
        return t;
    }

    private EditText field(String hint) {
        EditText e=new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setHint(hint);
        return e;
    }

    private Button button(String s) {
        Button b=new Button(this);
        b.setText(s);
        return b;
    }

    private void buildUi() {
        ScrollView page=new ScrollView(this);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16,12,16,16);
        root.setBackgroundColor(Color.rgb(247,249,252));

        page.addView(root);

        TextView title=label("Ruijie Voucher Printer 4.0");
        title.setTextSize(23);
        title.setTextColor(Color.rgb(20,55,95));
        root.addView(title);

        root.addView(label(
            "Manage User Groups, vouchers and Bluetooth printing."
        ));

        root.addView(label("BLUETOOTH PRINTER"));

        printerSpinner=new Spinner(this);
        root.addView(printerSpinner);

        Button refresh=button("Refresh paired printers");
        refresh.setOnClickListener(v->refreshPrinters());
        root.addView(refresh);

        root.addView(label("PAPER SIZE"));

        paperSpinner=new Spinner(this);
        paperSpinner.setAdapter(new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"58mm","80mm","Custom"}
        ));
        root.addView(paperSpinner);

        root.addView(label("Custom paper width (30–120mm)"));
        customWidth=field("Enter width in mm");
        customWidth.setText("58");
        root.addView(customWidth);

        root.addView(label("Left margin (mm)"));
        leftMargin=field("Left margin");
        leftMargin.setText("0");
        root.addView(leftMargin);

        root.addView(label("Right margin (mm)"));
        rightMargin=field("Right margin");
        rightMargin.setText("0");
        root.addView(rightMargin);

        root.addView(label("PRINT ALIGNMENT"));

        alignmentSpinner=new Spinner(this);
        alignmentSpinner.setAdapter(new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Left","Center","Right"}
        ));
        alignmentSpinner.setSelection(1);
        root.addView(alignmentSpinner);

        Button preview=button("Preview voucher");
        preview.setOnClickListener(v->showPreview());
        root.addView(preview);

        root.addView(label("USER GROUP PROFILE"));

        profileSpinner=new Spinner(this);
        root.addView(profileSpinner);

        groupName=field("Profile name e.g. 1Week");
        root.addView(groupName);

        groupPeriod=field("Validity e.g. 7 days");
        root.addView(groupPeriod);

        groupQuota=field("Data quota e.g. 30GB");
        root.addView(groupQuota);

        groupUpload=field("Upload speed e.g. 10 Mbps");
        root.addView(groupUpload);

        groupDownload=field("Download speed e.g. 5 Mbps");
        root.addView(groupDownload);

        Button saveProfile=button("Save / Update User Group");
        saveProfile.setOnClickListener(v->saveProfile());
        root.addView(saveProfile);

        profileSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(
                    AdapterView<?> a, View v, int pos, long id
                ) {
                    if(pos>=0 && pos<profiles.size())
                        showProfile(profiles.get(pos));
                }

                public void onNothingSelected(AdapterView<?> a) {}
            }
        );

        refreshProfiles();

        root.addView(label("ADD VOUCHER CODES"));

        root.addView(label(
            "Paste one code per line. Select the User Group above."
        ));

        codeInput=new EditText(this);
        codeInput.setHint("38084063\n48184177\n44572610");
        codeInput.setMinLines(3);
        codeInput.setGravity(48);
        codeInput.setTextSize(15);
        root.addView(codeInput);

        Button addCodes=button("Add pasted codes");
        addCodes.setOnClickListener(v->addPastedCodes());
        root.addView(addCodes);

        Button importButton=button("Import CSV file");
        importButton.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i,PICK_CSV);
        });
        root.addView(importButton);

        root.addView(label("VOUCHERS"));

        Button all=button("Select all vouchers");
        all.setOnClickListener(v->{
            for(int i=0;i<voucherList.getChildCount();i++) {
                View c=voucherList.getChildAt(i);
                if(c instanceof CheckBox)
                    ((CheckBox)c).setChecked(true);
            }
        });
        root.addView(all);

        Button none=button("Deselect all");
        none.setOnClickListener(v->{
            for(int i=0;i<voucherList.getChildCount();i++) {
                View c=voucherList.getChildAt(i);
                if(c instanceof CheckBox)
                    ((CheckBox)c).setChecked(false);
            }
        });
        root.addView(none);

        Button print=button("PRINT SELECTED VIA BLUETOOTH");
        print.setOnClickListener(v->printSelected());
        root.addView(print);

        status=label("Ready. Add a user group or voucher code.");
        root.addView(status);

        voucherList=new LinearLayout(this);
        voucherList.setOrientation(LinearLayout.VERTICAL);
        root.addView(voucherList);

        setContentView(page);
    }
    
    private void refreshProfiles() {
        ArrayAdapter<Profile> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            profiles
        );
        profileSpinner.setAdapter(adapter);
    }

    private void showProfile(Profile p) {
        if (p == null) return;

        groupName.setText(p.name);
        groupPeriod.setText(p.period);
        groupQuota.setText(p.quota);
        groupUpload.setText(p.upload);
        groupDownload.setText(p.download);
    }

    private void saveProfile() {
        String name = groupName.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a profile name",
                Toast.LENGTH_SHORT).show();
            return;
        }

        Profile selected = null;

        for (Profile p : profiles) {
            if (p.name.equalsIgnoreCase(name)) {
                selected = p;
                break;
            }
        }

        if (selected == null) {
            selected = new Profile(
                name,
                groupPeriod.getText().toString().trim(),
                groupQuota.getText().toString().trim(),
                groupUpload.getText().toString().trim(),
                groupDownload.getText().toString().trim()
            );
            profiles.add(selected);
        } else {
            selected.period = groupPeriod.getText().toString().trim();
            selected.quota = groupQuota.getText().toString().trim();
            selected.upload = groupUpload.getText().toString().trim();
            selected.download = groupDownload.getText().toString().trim();
        }

        refreshProfiles();

        int position = profiles.indexOf(selected);
        if (position >= 0) {
            profileSpinner.setSelection(position);
        }

        status.setText("User Group saved: " + name);

        Toast.makeText(this, "User Group saved",
            Toast.LENGTH_SHORT).show();
    }

    private Profile getSelectedProfile() {
        int position = profileSpinner.getSelectedItemPosition();

        if (position < 0 || position >= profiles.size()) {
            return null;
        }

        return profiles.get(position);
    }

    private void addPastedCodes() {
        Profile profile = getSelectedProfile();

        if (profile == null) {
            Toast.makeText(this, "Select a User Group first",
                Toast.LENGTH_SHORT).show();
            return;
        }

        String input = codeInput.getText().toString().trim();

        if (input.isEmpty()) {
            Toast.makeText(this, "Paste voucher codes first",
                Toast.LENGTH_SHORT).show();
            return;
        }

        int added = 0;

        String[] lines = input.split("\\r?\\n");

        for (String line : lines) {
            String code = line.trim();

            if (code.isEmpty()) continue;

            String[] parts = code.split("[,;\\t]", 2);

            String voucherCode = parts[0].trim();
            String password = "";

            if (parts.length > 1) {
                password = parts[1].trim();
            }

            if (voucherCode.isEmpty()) continue;

            if (seen.add(voucherCode)) {
                vouchers.add(new Voucher(
                    voucherCode,
                    password,
                    profile
                ));
                added++;
            }
        }

        codeInput.setText("");
        refreshVoucherList();

        status.setText(
            added + " new voucher(s) added. Total: " +
            vouchers.size()
        );

        Toast.makeText(this, added + " voucher(s) added",
            Toast.LENGTH_SHORT).show();
    }

    private void refreshVoucherList() {
        voucherList.removeAllViews();

        for (Voucher voucher : vouchers) {
            CheckBox check = new CheckBox(this);

            String details = voucher.code;

            if (voucher.password != null &&
                !voucher.password.isEmpty()) {
                details += " / " + voucher.password;
            }

            if (voucher.profile != null) {
                details += "  |  " + voucher.profile.name;
            }

            check.setText(details);
            check.setTextSize(14);
            check.setTag(voucher);

            voucherList.addView(check);
        }
    }

    private String voucherText(Voucher v) {
        StringBuilder text = new StringBuilder();

        text.append("WI-FI VOUCHER\n");
        text.append("------------------------\n");

        text.append("User: ");
        text.append(v.code);
        text.append("\n");

        if (v.password != null && !v.password.isEmpty()) {
            text.append("Password: ");
            text.append(v.password);
            text.append("\n");
        }

        if (v.profile != null) {
            text.append("Group: ");
            text.append(v.profile.name);
            text.append("\n");

            if (!v.profile.period.isEmpty()) {
                text.append("Validity: ");
                text.append(v.profile.period);
                text.append("\n");
            }

            if (!v.profile.quota.isEmpty()) {
                text.append("Quota: ");
                text.append(v.profile.quota);
                text.append("\n");
            }

            if (!v.profile.upload.isEmpty()) {
                text.append("Upload: ");
                text.append(v.profile.upload);
                text.append("\n");
            }

            if (!v.profile.download.isEmpty()) {
                text.append("Download: ");
                text.append(v.profile.download);
                text.append("\n");
            }
        }

        text.append("------------------------\n");
        return text.toString();
    }

    private int getPaperWidthMm() {
        int selected = paperSpinner.getSelectedItemPosition();

        if (selected == 0) return 58;
        if (selected == 1) return 80;

        try {
            int width = Integer.parseInt(
                customWidth.getText().toString().trim()
            );

            return Math.max(30, Math.min(120, width));
        } catch (Exception e) {
            return 58;
        }
    }

    private int getLeftMarginMm() {
        try {
            return Math.max(0, Integer.parseInt(
                leftMargin.getText().toString().trim()
            ));
        } catch (Exception e) {
            return 0;
        }
    }

    private int getRightMarginMm() {
        try {
            return Math.max(0, Integer.parseInt(
                rightMargin.getText().toString().trim()
            ));
        } catch (Exception e) {
            return 0;
        }
    }

    private void showPreview() {
        Voucher sample = null;

        for (int i = 0; i < voucherList.getChildCount(); i++) {
            View view = voucherList.getChildAt(i);

            if (view instanceof CheckBox &&
                ((CheckBox) view).isChecked()) {
                sample = (Voucher) view.getTag();
                break;
            }
        }

        if (sample == null && !vouchers.isEmpty()) {
            sample = vouchers.get(0);
        }

        if (sample == null) {
            Profile profile = getSelectedProfile();

            if (profile == null) {
                Toast.makeText(this, "Add a voucher first",
                    Toast.LENGTH_SHORT).show();
                return;
            }

            sample = new Voucher("SAMPLE123", "", profile);
        }

        String alignment = alignmentSpinner.getSelectedItem()
            .toString();

        String previewText =
            "Paper width: " + getPaperWidthMm() + " mm\n" +
            "Left margin: " + getLeftMarginMm() + " mm\n" +
            "Right margin: " + getRightMarginMm() + " mm\n" +
            "Alignment: " + alignment + "\n\n" +
            voucherText(sample);

        TextView preview = new TextView(this);
        preview.setText(previewText);
        preview.setTextSize(16);
        preview.setTextColor(Color.BLACK);
        preview.setPadding(20, 20, 20, 20);

        new AlertDialog.Builder(this)
            .setTitle("Voucher Print Preview")
            .setView(preview)
            .setPositiveButton("Close", null)
            .show();
    }
    
    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            !hasBluetoothPermission()) {
            requestPermissions(
                new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                },
                REQ_BT
            );
        }
    }

    private void refreshPrinters() {
        if (bluetooth == null) {
            status.setText("This device has no Bluetooth adapter.");
            return;
        }

        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            status.setText("Allow Bluetooth permission, then refresh.");
            return;
        }

        printerDevices.clear();

        ArrayList<String> names = new ArrayList<>();

        try {
            if (!bluetooth.isEnabled()) {
                status.setText("Please turn on Bluetooth.");
                return;
            }

            Set<BluetoothDevice> paired =
                bluetooth.getBondedDevices();

            for (BluetoothDevice device : paired) {
                printerDevices.add(device);

                String name = device.getName();
                if (name == null) name = "Bluetooth printer";

                names.add(name + " (" + device.getAddress() + ")");
            }

        } catch (SecurityException e) {
            status.setText("Bluetooth permission denied.");
            return;
        }

        if (names.isEmpty()) {
            names.add("No paired printers found");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            names
        );

        printerSpinner.setAdapter(adapter);

        status.setText(
            printerDevices.size() + " paired device(s) found."
        );
    }

    private BluetoothDevice getSelectedPrinter() {
        int position = printerSpinner.getSelectedItemPosition();

        if (position < 0 || position >= printerDevices.size()) {
            return null;
        }

        return printerDevices.get(position);
    }

    private void connectAndPrint(ArrayList<Voucher> selected) {
        if (!hasBluetoothPermission()) {
            runOnUiThread(() -> requestBluetoothPermission());
            return;
        }

        BluetoothDevice device = getSelectedPrinter();

        if (device == null) {
            runOnUiThread(() ->
                status.setText("Select a paired printer first.")
            );
            return;
        }

        BluetoothSocket newSocket = null;

        try {
            newSocket = device.createRfcommSocketToServiceRecord(SPP);

            newSocket.connect();

            OutputStream output = newSocket.getOutputStream();

            int widthMm = getPaperWidthMm();
            int leftMm = getLeftMarginMm();
            int rightMm = getRightMarginMm();

            int widthDots = Math.round(widthMm * 203f / 25.4f);
            int leftDots = Math.round(leftMm * 203f / 25.4f);
            int rightDots = Math.round(rightMm * 203f / 25.4f);

            int printableDots = widthDots - leftDots - rightDots;

            if (printableDots < 100) {
                throw new IOException("Margins leave too little print width.");
            }

            int alignment = alignmentSpinner.getSelectedItemPosition();

            output.write(new byte[]{0x1B, 0x40});

            output.write(new byte[]{
                0x1D, 0x4C,
                (byte)(leftDots & 0xFF),
                (byte)((leftDots >> 8) & 0xFF)
            });

            output.write(new byte[]{
                0x1D, 0x57,
                (byte)(printableDots & 0xFF),
                (byte)((printableDots >> 8) & 0xFF)
            });

            output.write(new byte[]{
                0x1B, 0x61, (byte)alignment
            });

            for (Voucher voucher : selected) {
                String ticket = voucherText(voucher);

                output.write(ticket.getBytes(Charset.forName("UTF-8")));
                output.write(new byte[]{0x0A, 0x0A});
                output.flush();
            }

            output.write(new byte[]{0x1B, 0x64, 0x03});
            output.flush();

            newSocket.close();

            runOnUiThread(() ->
                status.setText(selected.size() + " voucher(s) sent to printer.")
            );

        } catch (Exception e) {
            try {
                if (newSocket != null) newSocket.close();
            } catch (Exception ignored) {}

            String message = e.getMessage();

            runOnUiThread(() ->
                status.setText("Print failed: " + message)
            );
        }
    }

    private void printSelected() {
        ArrayList<Voucher> selected = new ArrayList<>();

        for (int i = 0; i < voucherList.getChildCount(); i++) {
            View view = voucherList.getChildAt(i);

            if (view instanceof CheckBox) {
                CheckBox check = (CheckBox)view;

                if (check.isChecked()) {
                    selected.add((Voucher)check.getTag());
                }
            }
        }

        if (selected.isEmpty()) {
            Toast.makeText(this, "Select vouchers to print",
                Toast.LENGTH_SHORT).show();
            return;
        }

        if (bluetooth == null) {
            status.setText("Bluetooth is not available.");
            return;
        }

        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }

        status.setText("Connecting to printer...");

        worker.execute(() -> connectAndPrint(selected));
    }

    @Override
    protected void onActivityResult(
        int requestCode, int resultCode, Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_CSV &&
            resultCode == RESULT_OK &&
            data != null &&
            data.getData() != null) {

            importCsv(data.getData());
        }
    }

    private void importCsv(Uri uri) {
        Profile profile = getSelectedProfile();

        if (profile == null) {
            Toast.makeText(this, "Select a User Group first",
                Toast.LENGTH_SHORT).show();
            return;
        }

        int added = 0;

        try {
            InputStream input = getContentResolver()
                .openInputStream(uri);

            if (input == null) {
                throw new IOException("Cannot open CSV file");
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, Charset.forName("UTF-8"))
            );

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) continue;

                String[] columns = line.split("[,;\\t]", -1);

                String code = columns[0].trim();

                if (code.isEmpty()) continue;

                String lower = code.toLowerCase(Locale.ROOT);

                if (lower.equals("code") ||
                    lower.equals("username") ||
                    lower.equals("user") ||
                    lower.equals("voucher") ||
                    lower.equals("account")) {
                    continue;
                }

                String password = "";

                if (columns.length > 1) {
                    password = columns[1].trim();
                }

                if (seen.add(code)) {
                    vouchers.add(new Voucher(
                        code,
                        password,
                        profile
                    ));
                    added++;
                }
            }

            reader.close();

            refreshVoucherList();

            status.setText(
                added + " CSV voucher(s) imported. Total: " +
                vouchers.size()
            );

        } catch (Exception e) {
            status.setText("CSV import failed: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        );

        if (requestCode == REQ_BT) {
            if (hasBluetoothPermission()) {
                refreshPrinters();
            } else {
                status.setText("Bluetooth permission was not granted.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        worker.shutdownNow();

        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}
