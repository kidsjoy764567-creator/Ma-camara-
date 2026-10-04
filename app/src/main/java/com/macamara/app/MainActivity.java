package com.macamara.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends ComponentActivity {
    static final int REQ_CAPTURE=40;
    static final int REQ_NOTIFY=41;

    private final Handler h=new Handler(Looper.getMainLooper());
    private MemoryStore db;
    private TextView status,timer,clock,clips,vehicles,incidents,badge;
    private Button start,remember;
    private PreviewView cameraPreview;
    private long started=0;

    private final BroadcastReceiver serviceReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(RecordingService.ACTION_READY.equals(action)){
                started=System.currentTimeMillis();
                start.setText("STOP & SAVE MEMORY");
                remember.setEnabled(true);
                badge.setText("● REC");
                badge.setTextColor(ContextCompat.getColor(MainActivity.this,R.color.red));
                status.setText("LIVE CAMERA • visible recording");
                findViewById(R.id.previewLabel).setVisibility(View.GONE);
            }else if(RecordingService.ACTION_ERROR.equals(action)){
                started=0;
                start.setText("START MEMORY");
                remember.setEnabled(false);
                badge.setText("● ERROR");
                badge.setTextColor(ContextCompat.getColor(MainActivity.this,R.color.red));
                findViewById(R.id.previewLabel).setVisibility(View.VISIBLE);
                String msg=intent.getStringExtra(RecordingService.EXTRA_ERROR);
                status.setText("Camera stopped — "+(msg==null?"unknown error":msg));
                Toast.makeText(MainActivity.this,"MA CAMARA: "+(msg==null?"Camera failed.":msg),Toast.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(clock!=null){
                clock.setText(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date()));
            }
            if(started>0 && timer!=null){
                long s=(System.currentTimeMillis()-started)/1000;
                timer.setText(String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60));
            }
            h.postDelayed(this,1000);
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        db=new MemoryStore(this);
        status=findViewById(R.id.subtitle);
        clock=findViewById(R.id.clock);
        timer=findViewById(R.id.timer);
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
        remember.setOnClickListener(v->{
            db.incidentNear(System.currentTimeMillis());
            Toast.makeText(this,"Incident marked.",Toast.LENGTH_SHORT).show();
            refresh();
        });

        refresh();
        h.post(tick);
    }

    @Override protected void onStart(){
        super.onStart();
        IntentFilter filter=new IntentFilter();
        filter.addAction(RecordingService.ACTION_READY);
        filter.addAction(RecordingService.ACTION_ERROR);
        if(Build.VERSION.SDK_INT>=33){
            registerReceiver(serviceReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        }else{
            registerReceiver(serviceReceiver,filter);
        }
        RecordingService.attachPreview(cameraPreview);
    }

    @Override protected void onStop(){
        RecordingService.detachPreview(cameraPreview);
        try{ unregisterReceiver(serviceReceiver); }catch(IllegalArgumentException ignored){}
        super.onStop();
    }

    private void requestStart(){
        ArrayList<String> missing=new ArrayList<>();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            missing.add(Manifest.permission.CAMERA);
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            missing.add(Manifest.permission.RECORD_AUDIO);
        }

        if(missing.isEmpty()){
            startMemory();
        }else{
            ActivityCompat.requestPermissions(this,missing.toArray(new String[0]),REQ_CAPTURE);
        }

        if(Build.VERSION.SDK_INT>=33 &&
           ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);
        }
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==REQ_CAPTURE){
            boolean ok=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            if(ok) startMemory();
            else Toast.makeText(this,"Camera and microphone permissions are required.",Toast.LENGTH_LONG).show();
        }
    }

    private void startMemory(){
        try{
            Intent intent=new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_START);
            ContextCompat.startForegroundService(this,intent);
            start.setText("STARTING…");
            start.setEnabled(false);
            remember.setEnabled(false);
            badge.setText("● STARTING");
            status.setText("Opening live camera…");
            findViewById(R.id.previewLabel).setVisibility(View.VISIBLE);

            h.postDelayed(()->{
                if(!RecordingService.isRecording && start!=null && !isFinishing()){
                    start.setEnabled(true);
                    start.setText("START MEMORY");
                    badge.setText("● ERROR");
                    status.setText("Camera did not start. Check permissions and camera availability.");
                }
            },7000);
        }catch(Throwable t){
            start.setEnabled(true);
            Toast.makeText(this,"Could not start camera: "+errorMessage(t),Toast.LENGTH_LONG).show();
        }
    }

    private void stopMemory(){
        Intent i=new Intent(this,RecordingService.class).setAction(RecordingService.ACTION_STOP);
        startService(i);
        started=0;
        start.setEnabled(true);
        start.setText("START MEMORY");
        remember.setEnabled(false);
        badge.setText("● READY");
        status.setText("Saving memory…");
        findViewById(R.id.previewLabel).setVisibility(View.VISIBLE);
        refresh();
    }

    private String errorMessage(Throwable t){
        return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();
    }

    private void refresh(){
        clips.setText(db.count(null)+"\nCLIPS");
        vehicles.setText(db.count("vehicle IS NOT NULL AND vehicle<>''")+"\nVEHICLES");
        incidents.setText(db.count("incident=1")+"\nINCIDENTS");
    }

    private void showMemory(){
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

    private void showSearch(){
        EditText q=new EditText(this);
        q.setSingleLine(true);
        q.setHint("vehicle, plate or time");
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

    private String label(String[] x){
        String when=new SimpleDateFormat("dd MMM, HH:mm:ss",Locale.US).format(new Date(Long.parseLong(x[2])));
        String v=(x[4]==null||x[4].isEmpty())?"vehicle not identified":x[4];
        String p=(x[5]==null||x[5].isEmpty())?"plate unreadable":x[5];
        return ("1".equals(x[7])?"★ INCIDENT  ":"")+when+"\n"+v+" • "+p;
    }

    private void play(String id){
        String p=db.path(Long.parseLong(id));
        if(p==null||!new File(p).exists()){
            Toast.makeText(this,"This memory file is missing.",Toast.LENGTH_SHORT).show();
            return;
        }
        VideoView vv=new VideoView(this);
        vv.setVideoURI(Uri.fromFile(new File(p)));
        vv.setMediaController(new MediaController(this));
        vv.setOnErrorListener((v,what,extra)->{
            Toast.makeText(this,"Unable to play this memory clip.",Toast.LENGTH_SHORT).show();
            return true;
        });
        new AlertDialog.Builder(this).setTitle("MEMORY CLIP").setView(vv).setPositiveButton("CLOSE",null).show();
        vv.start();
    }

    private LinearLayout box(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(18,8,18,8);
        return l;
    }

    private View wrap(View v){
        ScrollView s=new ScrollView(this);
        s.addView(v);
        s.setPadding(8,8,8,8);
        return s;
    }

    private void add(LinearLayout l,String text){
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(this,R.color.text));
        t.setPadding(8,18,8,18);
        l.addView(t);
    }

    @Override protected void onDestroy(){
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}
