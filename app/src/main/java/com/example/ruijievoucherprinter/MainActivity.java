package com.example.ruijievoucherprinter;

import android.Manifest;
import android.app.Activity;
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
        Profile(String n,String p,String q,String u,String d){name=n;period=p;quota=q;upload=u;download=d;}
        @Override public String toString(){return name;}
    }
    static class Voucher {
        String code,password; Profile profile;
        Voucher(String c,String pw,Profile pr){code=c;password=pw;profile=pr;}
    }
    private static final int PICK_CSV=41, REQ_BT=42;
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final ArrayList<Profile> profiles=new ArrayList<>();
    private final ArrayList<Voucher> vouchers=new ArrayList<>();
    private final HashSet<String> seen=new HashSet<>();
    private final ArrayList<BluetoothDevice> printerDevices=new ArrayList<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout voucherList;
    private TextView status;
    private Spinner printerSpinner, paperSpinner, profileSpinner;
    private EditText groupName, groupPeriod, groupQuota, groupUpload, groupDownload, codeInput;
    private BluetoothAdapter bluetooth;
    private BluetoothSocket socket;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        bluetooth=BluetoothAdapter.getDefaultAdapter();
        seedProfiles();
        buildUi();
        refreshPrinters();
    }
    private void seedProfiles(){
        profiles.add(new Profile("1Month","30 days","","",""));
        profiles.add(new Profile("1Week","7 days","30GB","",""));
        profiles.add(new Profile("1Day","1 day","","",""));
        profiles.add(new Profile("5Hour","300 minutes","","",""));
        profiles.add(new Profile("3Hour","180 minutes","","",""));
        profiles.add(new Profile("2Hour","2 hours","4GB","",""));
        profiles.add(new Profile("1Hour","1 hour","","",""));
    }
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.rgb(35,55,75));t.setTextSize(14);t.setPadding(0,7,0,3);return t;}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(14);e.setHint(hint);return e;}
    private Button button(String s){Button b=new Button(this);b.setText(s);return b;}
    private void buildUi(){
        ScrollView page=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(16,12,16,16);
        root.setBackgroundColor(Color.rgb(247,249,252));page.addView(root);
        TextView title=label("Ruijie Voucher Printer 3.0");title.setTextSize(23);title.setTextColor(Color.rgb(20,55,95));root.addView(title);
        TextView intro=label("Manage User Groups, add voucher codes, and print tickets using a Bluetooth thermal printer.");root.addView(intro);

        root.addView(label("Bluetooth printer"));
        printerSpinner=new Spinner(this);root.addView(printerSpinner);
        Button refresh=button("Refresh paired printers");refresh.setOnClickListener(v->refreshPrinters());root.addView(refresh);
        root.addView(label("Paper size"));
        paperSpinner=new Spinner(this);paperSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"58mm","80mm"}));root.addView(paperSpinner);

        root.addView(label("USER GROUP PROFILE"));
        profileSpinner=new Spinner(this);root.addView(profileSpinner);
        groupName=field("Profile name e.g. 1Week");root.addView(groupName);
        groupPeriod=field("Validity e.g. 7 days");root.addView(groupPeriod);
        groupQuota=field("Data quota e.g. 30GB");root.addView(groupQuota);
        groupUpload=field("Upload speed e.g. 10 Mbps");root.addView(groupUpload);
        groupDownload=field("Download speed e.g. 5 Mbps");root.addView(groupDownload);
        Button saveProfile=button("Save / Update User Group");saveProfile.setOnClickListener(v->saveProfile());root.addView(saveProfile);
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){if(pos>=0&&pos<profiles.size())showProfile(profiles.get(pos));}
            public void onNothingSelected(AdapterView<?> a){}
        });
        refreshProfiles();

        root.addView(label("ADD VOUCHER CODES"));
        TextView help=label("Paste one code per line. Select the User Group above to assign it to every pasted code.");root.addView(help);
        codeInput=new EditText(this);codeInput.setHint("38084063\n48184177\n44572610");codeInput.setMinLines(3);codeInput.setGravity(48);codeInput.setTextSize(15);root.addView(codeInput);
        Button addCodes=button("Add pasted codes");addCodes.setOnClickListener(v->addPastedCodes());root.addView(addCodes);
        Button import=button("Import CSV file");import.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,PICK_CSV);});root.addView(import);

        root.addView(label("VOUCHERS"));
        Button all=button("Select all vouchers");all.setOnClickListener(v->{for(int i=0;i<voucherList.getChildCount();i++){View c=voucherList.getChildAt(i);if(c instanceof CheckBox)((CheckBox)c).setChecked(true);}});root.addView(all);
        Button none=button("Deselect all");none.setOnClickListener(v->{for(int i=0;i<voucherList.getChildCount();i++){View c=voucherList.getChildAt(i);if(c instanceof CheckBox)((CheckBox)c).setChecked(false);}});root.addView(none);
        Button print=button("PRINT SELECTED VIA BLUETOOTH");print.setOnClickListener(v->printSelected());root.addView(print);
        status=label("Ready. Add a user group or voucher code.");root.addView(status);
        voucherList=new LinearLayout(this);voucherList.setOrientation(LinearLayout.VERTICAL);root.addView(voucherList);
        setContentView(page);
    }
    private void refreshProfiles(){
        ArrayAdapter<Profile> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,profiles);
        profileSpinner.setAdapter(ad);
        if(!profiles.isEmpty())profileSpinner.setSelection(0);
    }
    private void showProfile(Profile p){
        if(groupName==null)return;
        groupName.setText(p.name);groupPeriod.setText(p.period);groupQuota.setText(p.quota);groupUpload.setText(p.upload);groupDownload.setText(p.download);
    }
    private void saveProfile(){
        String n=val(groupName), per=val(groupPeriod);
        if(n.isEmpty()){status.setText("Enter a User Group profile name.");return;}
        Profile found=null;
        for(Profile p:profiles)if(p.name.equalsIgnoreCase(n)){found=p;break;}
        if(found==null){found=new Profile(n,per,val(groupQuota),val(groupUpload),val(groupDownload));profiles.add(found);}
        else{found.period=per;found.quota=val(groupQuota);found.upload=val(groupUpload);found.download=val(groupDownload);}
        int pos=profiles.indexOf(found);refreshProfiles();profileSpinner.setSelection(pos);
        status.setText("Saved User Group: "+n+". Existing vouchers assigned to this group use the updated settings.");
        refreshVoucherList();
    }
    private String val(EditText e){return e.getText().toString().trim();}
    private Profile selectedProfile(){int i=profileSpinner.getSelectedItemPosition();return i>=0&&i<profiles.size()?profiles.get(i):null;}
    private void addPastedCodes(){
        Profile pr=selectedProfile();if(pr==null){status.setText("Create or select a User Group first.");return;}
        String raw=val(codeInput);if(raw.isEmpty()){status.setText("Paste voucher codes first.");return;}
        int added=0,dup=0;
        String[] lines=raw.split("[\\r\n,;\\t ]+");
        for(String line:lines){String code=line.trim();if(code.isEmpty())continue;
            String key=code.toLowerCase(Locale.ROOT);if(!seen.add(key)){dup++;continue;}
            vouchers.add(new Voucher(code,"",pr));added++;
        }
        codeInput.setText("");refreshVoucherList();status.setText("Added "+added+" voucher(s); skipped "+dup+" duplicate(s). Total: "+vouchers.size());
    }
    private boolean hasBtPermission(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void ensureBtPermission(){if(Build.VERSION.SDK_INT>=31&&!hasBtPermission())requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},REQ_BT);else refreshPrinters();}
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] grants){super.onRequestPermissionsResult(req,perms,grants);if(req==REQ_BT){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)refreshPrinters();else status.setText("Bluetooth permission is required.");}}
    private void refreshPrinters(){
        if(bluetooth==null){if(status!=null)status.setText("This phone has no Bluetooth adapter.");return;}
        if(!hasBtPermission()){ensureBtPermission();return;}
        ArrayList<String> names=new ArrayList<>();printerDevices.clear();
        try{if(bluetooth.getBondedDevices()!=null)for(BluetoothDevice d:bluetooth.getBondedDevices()){printerDevices.add(d);names.add(d.getName()+" ("+d.getAddress()+")");}}
        catch(SecurityException e){if(status!=null)status.setText("Bluetooth permission denied.");}
        if(names.isEmpty())names.add("No paired devices - pair printer in Android Settings");
        printerSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==PICK_CSV&&res==RESULT_OK&&data!=null&&data.getData()!=null){try{readCsv(data.getData());}catch(Exception e){status.setText("CSV import error: "+e.getMessage());}}
    }
    private void readCsv(Uri uri)throws Exception{
        InputStream in=getContentResolver().openInputStream(uri);if(in==null)throw new IOException("Cannot open file");
        BufferedReader br=new BufferedReader(new InputStreamReader(in,Charset.forName("UTF-8")));
        String line;int added=0,dup=0;boolean first=true;
        while((line=br.readLine())!=null){if(line.trim().isEmpty())continue;List<String> c=parseCsv(line);
            if(first){first=false;String h=line.toLowerCase(Locale.ROOT);if(h.contains("code")||h.contains("voucher"))continue;}
            String code=cell(c,0);if(code.isEmpty())continue;
            String profileName=cell(c,1);Profile pr=findProfile(profileName);if(pr==null)pr=selectedProfile();
            if(pr==null)pr=profiles.get(0);
            String pw=cell(c,2);String key=code.toLowerCase(Locale.ROOT);
            if(!seen.add(key)){dup++;continue;}vouchers.add(new Voucher(code,pw,pr));added++;
        }
        br.close();refreshVoucherList();status.setText("Imported "+added+" voucher(s); skipped "+dup+" duplicates.");
    }
    private Profile findProfile(String name){for(Profile p:profiles)if(p.name.equalsIgnoreCase(name))return p;return null;}
    private String cell(List<String> a,int i){return i<a.size()?a.get(i).trim():"";}
    private List<String> parseCsv(String s){ArrayList<String> out=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}
            else if(c==','&&!q){out.add(b.toString());b.setLength(0);}else b.append(c);}out.add(b.toString());return out;}
    private void refreshVoucherList(){
        if(voucherList==null)return;voucherList.removeAllViews();
        for(Voucher v:vouchers){CheckBox cb=new CheckBox(this);String detail=v.code+"  |  "+v.profile.name+"  |  "+v.profile.period;
            if(!v.profile.quota.isEmpty())detail+="  |  "+v.profile.quota;
            cb.setText(detail);cb.setTextSize(13);cb.setTag(v);cb.setChecked(true);voucherList.addView(cb);}
    }
    private void printSelected(){
        if(printerDevices.isEmpty()){status.setText("No paired printer. Pair it in Android Settings and refresh.");return;}
        if(!hasBtPermission()){ensureBtPermission();return;}
        int idx=printerSpinner.getSelectedItemPosition();if(idx<0||idx>=printerDevices.size()){status.setText("Select a paired printer.");return;}
        ArrayList<Voucher> chosen=new ArrayList<>();
        for(int i=0;i<voucherList.getChildCount();i++){View c=voucherList.getChildAt(i);if(c instanceof CheckBox&&((CheckBox)c).isChecked())chosen.add((Voucher)((CheckBox)c).getTag());}
        if(chosen.isEmpty()){status.setText("Select at least one voucher.");return;}
        BluetoothDevice device=printerDevices.get(idx);boolean wide=paperSpinner.getSelectedItemPosition()==1;
        status.setText("Connecting to "+device.getName()+"...");
        worker.execute(()->{try{
            BluetoothSocket s=device.createRfcommSocketToServiceRecord(SPP);s.connect();OutputStream out=s.getOutputStream();
            out.write(new byte[]{0x1B,0x40});
            for(Voucher v:chosen){out.write(ticketBytes(v));out.write(new byte[]{0x0A,0x0A,0x1D,0x56,0x00});}
            out.flush();if(socket!=null)try{socket.close();}catch(Exception ignored){}socket=s;
            runOnUiThread(()->status.setText("Sent "+chosen.size()+" voucher(s) to "+device.getName()+"."));
        }catch(Exception e){runOnUiThread(()->status.setText("Print failed: "+e.getMessage()+". Check SPP + ESC/POS compatibility."));}});
    }
    private byte[] ticketBytes(Voucher v)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();Profile p=v.profile;
        b.write(new byte[]{0x1B,0x61,0x01,0x1B,0x45,0x01});
        b.write("WI-FI INTERNET\n".getBytes("UTF-8"));b.write(new byte[]{0x1B,0x45,0x00});
        b.write("VOUCHER CODE\n".getBytes("UTF-8"));b.write(new byte[]{0x1D,0x21,0x11});
        b.write(safe(v.code).getBytes("UTF-8"));b.write('\n');b.write(new byte[]{0x1D,0x21,0x00});
        if(!v.password.isEmpty())b.write(("Password: "+safe(v.password)+"\n").getBytes("UTF-8"));
        b.write(("User Group: "+safe(p.name)+"\n").getBytes("UTF-8"));
        if(!p.period.isEmpty())b.write(("Validity: "+safe(p.period)+"\n").getBytes("UTF-8"));
        if(!p.quota.isEmpty())b.write(("Quota: "+safe(p.quota)+"\n").getBytes("UTF-8"));
        if(!p.upload.isEmpty())b.write(("Upload: "+safe(p.upload)+"\n").getBytes("UTF-8"));
        if(!p.download.isEmpty())b.write(("Download: "+safe(p.download)+"\n").getBytes("UTF-8"));
        b.write("Thank you for using our Wi-Fi\n".getBytes("UTF-8"));
        b.write("------------------------------\n".getBytes("UTF-8"));b.write(new byte[]{0x1B,0x61,0x00});
        return b.toByteArray();
    }
    private String safe(String s){return s.replace("\\r"," ").replace("\n"," ");}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdown();try{if(socket!=null)socket.close();}catch(Exception ignored){}}
}
