package com.macamara.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends ComponentActivity {
    static final int REQ=40;
    Handler h=new Handler(Looper.getMainLooper());
    MemoryStore db;
    TextView status,timer,clock,clips,vehicles,incidents,badge;
    Button start,remember;
    PreviewView cameraPreview;
    long started=0;

    final BroadcastReceiver serviceErrorReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String msg=intent.getStringExtra(RecordingService.EXTRA_ERROR);
            started=0;
            start.setText("START MEMORY");
            remember.setEnabled(false);
            badge.setText("● ERROR");
            badge.setTextColor(ContextCompat.getColor(MainActivity.this,R.color.red));
            status.setText("Recording stopped — "+(msg==null?"camera error":msg));
            Toast.makeText(MainActivity.this,"MA CAMARA: "+(msg==null?"Camera failed to start.":msg),Toast.LENGTH_LONG).show();
        }
    };

    Runnable tick=new Runnable(){
        public void run(){
            clock.setText(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date()));
            if(started>0){
                long s=(System.currentTimeMillis()-started)/1000;
                timer.setText(String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60));
            }
            h.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        db=new MemoryStore(this);

        status=findViewById(R.id.subtitle);
        clock=findViewById(R.id.clock);
        clips=findViewById(R.id.statClips);
        vehicles=findViewById(R.id.statVehicles);
        incidents=findViewById(R.id.statIncidents);
        start=findViewById(R.id.startStop);
        remember=findViewById(R.id.remember);
        badge=findViewById(R.id.recBadge);
        cameraPreview=findViewById(R.id.cameraPreview);

        findViewById(R.id.timeline).setOnClickListener(v->showMemory());
        findViewById(R.id.search).setOnClickListener(v->showSearch());

        start.setOnClickListener(v->{
            if(RecordingService.isRecording) stopMemory();
            else requestStart();
        });
        remember.setOnClickListener(v->remember());

        refresh();
        h.post(tick);
    }

    @Override protected void onStart(){
        super.onStart();
        IntentFilter filter=new IntentFilter(RecordingService.ACTION_ERROR);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(serviceErrorReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceErrorReceiver,filter);
        RecordingService.attachPreview(cameraPreview);
    }

    @Override protected void onStop(){
        RecordingService.detachPreview(cameraPreview);
        try{unregisterReceiver(serviceErrorReceiver);}catch(IllegalArgumentException ignored){}
        super.onStop();
    }

    void requestStart(){
        String[] p={Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS};
        List<String> m=new ArrayList<>();
        for(String x:p){
            if(Build.VERSION.SDK_INT<33 && x.equals(Manifest.permission.POST_NOTIFICATIONS)) continue;
            if(ContextCompat.checkSelfPermission(this,x)!=PackageManager.PERMISSION_GRANTED) m.add(x);
        }
        if(m.isEmpty()) startMemory();
        else ActivityCompat.requestPermissions(this,m.toArray(new String[0]),REQ);
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ){
            boolean ok=true;
            for(int x:g) if(x!=PackageManager.PERMISSION_GRANTED) ok=false;
            if(ok) startMemory();
            else Toast.makeText(this,"Camera and microphone permissions are required for Memory.",Toast.LENGTH_LONG).show();
        }
    }

    void startMemory(){
        try{
            Intent intent=new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_START);
            ContextCompat.startForegroundService(this,intent);
            started=System.currentTimeMillis();
            start.setText("STOP & SAVE MEMORY");
            remember.setEnabled(true);
            badge.setText("● STARTING");
            badge.setTextColor(ContextCompat.getColor(this,R.color.red));
            status.setText("Starting live camera…");
        }catch(Throwable t){
            started=0;
            Toast.makeText(this,"Could not start camera: "+(t.getMessage()==null?t.getClass().getSimpleName():t.getMessage()),Toast.LENGTH_LONG).show();
        }
    }

    void stopMemory(){
        startService(new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_STOP));
        started=0;
        start.setText("START MEMORY");
        remember.setEnabled(false);
        badge.setText("● READY");
        badge.setTextColor(ContextCompat.getColor(this,R.color.muted));
        status.setText("Memory saved locally");
        refresh();
    }

    void remember(){
        db.incidentNear(System.currentTimeMillis());
        Toast.makeText(this,"Incident marked. Current memory clip is protected.",Toast.LENGTH_SHORT).show();
        refresh();
    }

    void refresh(){
        clips.setText(db.count(null)+"\nCLIPS");
        vehicles.setText(db.count("vehicle IS NOT NULL AND vehicle<>''")+"\nVEHICLES");
        incidents.setText(db.count("incident=1")+"\nINCIDENTS");
    }

    void showMemory(){
        List<String[]> a=db.recent();
        LinearLayout box=box();
        if(a.isEmpty()) add(box,"No memories yet.");
        for(String[] x:a){
            Button b=new Button(this);
            b.setText(label(x));
            b.setOnClickListener(v->play(x[0]));
            box.addView(b);
        }
        new AlertDialog.Builder(this).setTitle("MEMORY TIMELINE").setView(wrap(box)).setPositiveButton("CLOSE",null).show();
    }

    void showSearch(){
        EditText q=new EditText(this);
        q.setHint("car, plate, incident, Alto...");
        new AlertDialog.Builder(this).setTitle("WHAT DO YOU REMEMBER?").setView(q).setPositiveButton("SEARCH",(d,w)->{
            List<String[]> a=db.search(q.getText().toString());
            LinearLayout box=box();
            if(a.isEmpty()) add(box,"No indexed memory matched.");
            for(String[] x:a){
                Button b=new Button(this);
                b.setText(label(x));
                b.setOnClickListener(v->play(x[0]));
                box.addView(b);
            }
            new AlertDialog.Builder(this).setTitle("SEARCH RESULTS").setView(wrap(box)).setPositiveButton("CLOSE",null).show();
        }).setNegativeButton("CANCEL",null).show();
    }

    String label(String[] x){
        String when=new SimpleDateFormat("dd MMM, HH:mm:ss",Locale.US).format(new Date(Long.parseLong(x[2])));
        String v=(x[4]==null||x[4].isEmpty())?"vehicle not identified":x[4];
        String p=(x[5]==null||x[5].isEmpty())?"plate unreadable":x[5];
        return (x[7].equals("1")?"★ INCIDENT  ":"")+when+"\n"+v+" • "+p;
    }

    void play(String id){
        String p=db.path(Long.parseLong(id));
        if(p==null)return;
        VideoView vv=new VideoView(this);
        vv.setVideoURI(Uri.fromFile(new File(p)));
        vv.setMediaController(new android.widget.MediaController(this));
        vv.start();
        new AlertDialog.Builder(this).setTitle("MEMORY CLIP").setView(vv).setPositiveButton("CLOSE",null).show();
    }

    LinearLayout box(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(18,8,18,8);
        return l;
    }

    View wrap(View v){
        ScrollView s=new ScrollView(this);
        s.addView(v);
        s.setPadding(8,8,8,8);
        return s;
    }

    void add(LinearLayout l,String s){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(ContextCompat.getColor(this,R.color.text));
        t.setPadding(8,18,8,18);
        l.addView(t);
    }

    @Override protected void onDestroy(){
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}