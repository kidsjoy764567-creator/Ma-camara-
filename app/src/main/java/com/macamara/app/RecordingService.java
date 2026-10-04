package com.macamara.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.view.Surface;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.*;

public class RecordingService extends LifecycleService {
    public static final String ACTION_START="com.macamara.START";
    public static final String ACTION_STOP="com.macamara.STOP";
    public static final String ACTION_ERROR="com.macamara.ERROR";
    public static final String EXTRA_ERROR="error";
    public static volatile boolean isRecording=false;
    public static volatile String currentSegmentName="none";

    private static WeakReference<PreviewView> previewRef=new WeakReference<>(null);
    private static RecordingService instance;

    final Handler h=new Handler(Looper.getMainLooper());
    final ExecutorService ex=Executors.newSingleThreadExecutor();
    Recording recording;
    Recorder recorder;
    VideoCapture<Recorder> capture;
    Preview preview;
    boolean stopping;
    long segmentStart;
    MemoryStore db;

    public static void attachPreview(PreviewView v){
        previewRef=new WeakReference<>(v);
        if(instance!=null) instance.connectPreview();
    }

    public static void detachPreview(PreviewView v){
        PreviewView current=previewRef.get();
        if(current==v){
            previewRef.clear();
            if(instance!=null && instance.preview!=null) instance.preview.setSurfaceProvider(null);
        }
    }

    @Override public void onCreate(){
        super.onCreate();
        instance=this;
        db=new MemoryStore(this);
        channel();
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        super.onStartCommand(i,flags,id);
        try{
            if(i!=null && ACTION_STOP.equals(i.getAction())){
                stopping=true;
                isRecording=false;
                stopClip();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }

            if(i!=null && ACTION_START.equals(i.getAction()) && !isRecording){
                stopping=false;
                if(!hasCapturePermissions()){
                    fail("Camera or microphone permission is missing.");
                    return START_NOT_STICKY;
                }
                startAsForeground();
                camera();
            }
        }catch(Throwable t){
            fail(message(t));
        }
        return START_STICKY;
    }

    private boolean hasCapturePermissions(){
        return ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }

    private void startAsForeground(){
        Notification n=notification();
        int type=0;
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){
            type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        ServiceCompat.startForeground(this,17,n,type);
    }

    void camera(){
        final ListenableFuture<ProcessCameraProvider> fu=ProcessCameraProvider.getInstance(this);
        fu.addListener(() -> {
            try{
                ProcessCameraProvider p=fu.get();
                p.unbindAll();

                QualitySelector quality=QualitySelector.fromOrderedList(
                    Arrays.asList(Quality.FHD,Quality.HD,Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                );

                recorder=new Recorder.Builder()
                    .setQualitySelector(quality)
                    .build();
                capture=VideoCapture.withOutput(recorder);

                int rotation=Surface.ROTATION_0;
                PreviewView pv=previewRef.get();
                if(pv!=null && pv.getDisplay()!=null) rotation=pv.getDisplay().getRotation();

                preview=new Preview.Builder()
                    .setTargetRotation(rotation)
                    .build();

                connectPreview();

                if(!p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)){
                    fail("Back camera is not available.");
                    return;
                }

                p.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                );

                startClip();
            }catch(Throwable t){
                fail(message(t));
            }
        },ContextCompat.getMainExecutor(this));
    }

    void connectPreview(){
        if(preview==null) return;
        PreviewView v=previewRef.get();
        if(v!=null) preview.setSurfaceProvider(v.getSurfaceProvider());
    }

    void startClip(){
        if(stopping || capture==null || recorder==null) return;

        try{
            File d=new File(getExternalFilesDir(null),"Memory");
            if(!d.exists() && !d.mkdirs()) throw new IllegalStateException("Could not create Memory folder.");

            File file=new File(d,"MEMORY_"+System.currentTimeMillis()+".mp4");
            currentSegmentName=file.getName();
            segmentStart=System.currentTimeMillis();

            FileOutputOptions out=new FileOutputOptions.Builder(file).build();
            PendingRecording pending=recorder.prepareRecording(this,out);

            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
                pending=pending.withAudioEnabled();
            }

            recording=pending.start(ContextCompat.getMainExecutor(this),e -> {
                try{
                    if(e instanceof VideoRecordEvent.Finalize){
                        VideoRecordEvent.Finalize x=(VideoRecordEvent.Finalize)e;
                        long end=System.currentTimeMillis();
                        recording=null;
                        if(x.getError()==VideoRecordEvent.Finalize.ERROR_NONE && file.exists() && file.length()>0 && !stopping){
                            long cid=db.addClip(file.getAbsolutePath(),segmentStart,end);
                            ex.execute(() -> {
                                try{ ClipAnalyzer.analyze(this,cid,file,db); }catch(Throwable ignored){}
                            });
                            h.postDelayed(this::startClip,250);
                        }else if(x.getError()!=VideoRecordEvent.Finalize.ERROR_NONE && !stopping){
                            fail("Video recording failed: "+x.getError());
                        }
                    }
                }catch(Throwable t){
                    fail(message(t));
                }
            });

            isRecording=true;

            h.postDelayed(() -> {
                if(!stopping && recording!=null){
                    Recording r=recording;
                    recording=null;
                    try{r.stop();}catch(Throwable t){fail(message(t));}
                }
            },120000);
        }catch(Throwable t){
            fail(message(t));
        }
    }

    void stopClip(){
        h.removeCallbacksAndMessages(null);
        if(recording!=null){
            Recording r=recording;
            recording=null;
            try{r.stop();}catch(Throwable ignored){}
        }
    }

    private void fail(String msg){
        isRecording=false;
        stopping=true;
        Intent i=new Intent(ACTION_ERROR);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ERROR,msg==null?"Camera service failed.":msg);
        sendBroadcast(i);
        try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(Throwable ignored){}
        stopSelf();
    }

    private String message(Throwable t){
        String m=t.getMessage();
        return m==null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    Notification notification(){
        return new NotificationCompat.Builder(this,"mc")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MA CAMARA")
            .setContentText("Visible camera recording is active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    void channel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null) nm.createNotificationChannel(
                new NotificationChannel("mc","MA CAMARA recording",NotificationManager.IMPORTANCE_LOW)
            );
        }
    }

    @Override public void onDestroy(){
        stopping=true;
        isRecording=false;
        stopClip();
        if(preview!=null) preview.setSurfaceProvider(null);
        if(instance==this) instance=null;
        ex.shutdownNow();
        super.onDestroy();
    }
}