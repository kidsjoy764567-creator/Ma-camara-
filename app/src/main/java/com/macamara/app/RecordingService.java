package com.macamara.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.view.Surface;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingService extends LifecycleService {
    public static final String ACTION_START="com.macamara.START";
    public static final String ACTION_STOP="com.macamara.STOP";
    public static final String ACTION_READY="com.macamara.READY";
    public static final String ACTION_ERROR="com.macamara.ERROR";
    public static final String EXTRA_ERROR="error";

    public static volatile boolean isRecording=false;
    public static volatile String currentSegmentName="none";

    private static WeakReference<PreviewView> previewRef=new WeakReference<>(null);
    private static RecordingService instance;

    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService ex=Executors.newSingleThreadExecutor();

    private Recording recording;
    private Recorder recorder;
    private VideoCapture<Recorder> capture;
    private Preview preview;
    private MemoryStore db;
    private Runnable segmentStopTask;
    private boolean starting=false;
    private boolean stopping=false;

    public static void attachPreview(PreviewView v){
        previewRef=new WeakReference<>(v);
        if(instance!=null) instance.connectPreview();
    }

    public static void detachPreview(PreviewView v){
        PreviewView current=previewRef.get();
        if(current==v){
            previewRef.clear();
            if(instance!=null && instance.preview!=null){
                instance.preview.setSurfaceProvider(null);
            }
        }
    }

    @Override public void onCreate(){
        super.onCreate();
        instance=this;
        db=new MemoryStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        super.onStartCommand(intent,flags,startId);
        try{
            String action=intent==null?null:intent.getAction();

            if(ACTION_STOP.equals(action)){
                stopRecordingAndService();
                return START_NOT_STICKY;
            }

            if(ACTION_START.equals(action) && !isRecording && !starting){
                if(!hasCapturePermissions()){
                    fail("Camera and microphone permissions are required.");
                    return START_NOT_STICKY;
                }
                stopping=false;
                starting=true;
                startAsForeground();
                openCamera();
            }
        }catch(Throwable t){
            fail(message(t));
        }
        return START_NOT_STICKY;
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

    private void openCamera(){
        final ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if(stopping) return;
            try{
                ProcessCameraProvider provider=future.get();
                provider.unbindAll();

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
                if(pv!=null && pv.getDisplay()!=null){
                    rotation=pv.getDisplay().getRotation();
                }

                preview=new Preview.Builder()
                    .setTargetRotation(rotation)
                    .build();
                connectPreview();

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                );

                starting=false;
                startClip();
            }catch(Throwable t){
                fail(message(t));
            }
        },ContextCompat.getMainExecutor(this));
    }

    private void connectPreview(){
        if(preview==null) return;
        PreviewView v=previewRef.get();
        if(v!=null) preview.setSurfaceProvider(v.getSurfaceProvider());
    }

    private void startClip(){
        if(stopping || capture==null || recorder==null || recording!=null) return;

        try{
            File dir=new File(getExternalFilesDir(null),"Memory");
            if(!dir.exists() && !dir.mkdirs()){
                throw new IllegalStateException("Could not create the Memory folder.");
            }

            File file=new File(dir,"MEMORY_"+System.currentTimeMillis()+".mp4");
            currentSegmentName=file.getName();
            final long start=System.currentTimeMillis();
            segmentStopTask=null;

            FileOutputOptions output=new FileOutputOptions.Builder(file).build();
            PendingRecording pending=recorder.prepareRecording(this,output);
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
                pending=pending.withAudioEnabled();
            }

            recording=pending.start(ContextCompat.getMainExecutor(this),event -> {
                try{
                    if(event instanceof VideoRecordEvent.Start){
                        isRecording=true;
                        sendSimple(ACTION_READY);
                    }else if(event instanceof VideoRecordEvent.Finalize){
                        VideoRecordEvent.Finalize result=(VideoRecordEvent.Finalize)event;
                        recording=null;
                        if(segmentStopTask!=null) h.removeCallbacks(segmentStopTask);

                        long end=System.currentTimeMillis();
                        boolean valid=result.getError()==VideoRecordEvent.Finalize.ERROR_NONE
                            && file.exists() && file.length()>0;

                        if(valid){
                            final long clipId=db.addClip(file.getAbsolutePath(),start,end);
                            ex.execute(() -> {
                                try{ ClipAnalyzer.analyze(this,clipId,file,db); }
                                catch(Throwable ignored){}
                            });
                        }

                        if(!stopping){
                            if(!valid){
                                fail("Video recording failed: "+result.getError());
                            }else{
                                h.postDelayed(this::startClip,250);
                            }
                        }else{
                            isRecording=false;
                            stopForeground(STOP_FOREGROUND_REMOVE);
                            stopSelf();
                        }
                    }
                }catch(Throwable t){
                    fail(message(t));
                }
            });

            segmentStopTask=() -> {
                if(!stopping && recording!=null){
                    Recording r=recording;
                    try{ r.stop(); }
                    catch(Throwable t){ fail(message(t)); }
                }
            };
            h.postDelayed(segmentStopTask,120000);
        }catch(Throwable t){
            fail(message(t));
        }
    }

    private void stopRecordingAndService(){
        stopping=true;
        starting=false;
        isRecording=false;
        if(segmentStopTask!=null) h.removeCallbacks(segmentStopTask);
        if(recording!=null){
            Recording r=recording;
            try{ r.stop(); }
            catch(Throwable ignored){}
        }else{
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void fail(String msg){
        isRecording=false;
        starting=false;
        stopping=true;
        if(segmentStopTask!=null) h.removeCallbacks(segmentStopTask);
        Intent i=new Intent(ACTION_ERROR);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ERROR,msg==null?"Camera service failed.":msg);
        sendBroadcast(i);
        try{ stopForeground(STOP_FOREGROUND_REMOVE); }catch(Throwable ignored){}
        stopSelf();
    }

    private void sendSimple(String action){
        Intent i=new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private String message(Throwable t){
        String m=t.getMessage();
        return m==null || m.trim().isEmpty()?t.getClass().getSimpleName():m;
    }

    private Notification notification(){
        return new NotificationCompat.Builder(this,"mc")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MA CAMARA")
            .setContentText("Visible camera recording is active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null){
                nm.createNotificationChannel(new NotificationChannel(
                    "mc","MA CAMARA recording",NotificationManager.IMPORTANCE_LOW
                ));
            }
        }
    }

    @Override public void onDestroy(){
        stopping=true;
        isRecording=false;
        starting=false;
        if(segmentStopTask!=null) h.removeCallbacks(segmentStopTask);
        if(recording!=null){
            try{ recording.stop(); }catch(Throwable ignored){}
            recording=null;
        }
        if(preview!=null) preview.setSurfaceProvider(null);
        if(instance==this) instance=null;
        ex.shutdownNow();
        super.onDestroy();
    }
}
