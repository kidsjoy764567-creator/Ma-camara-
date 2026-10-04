package com.macamara.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordingService extends LifecycleService {
    public static final String ACTION_START = "com.macamara.START";
    public static final String ACTION_STOP = "com.macamara.STOP";
    public static volatile boolean isRecording = false;
    public static volatile String currentSegmentName = "none";

    private static final int NOTIFICATION_ID = 17;
    private static final String CHANNEL_ID = "ma_camara_recording";
    private static final long SEGMENT_MS = 120_000L;

    private final Handler handler = new Handler();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private Recording recording;
    private Recorder recorder;
    private VideoCapture<Recorder> videoCapture;
    private boolean stopping;
    private File currentFile;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopping = true;
            isRecording = false;
            stopCurrentRecording();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else if (intent != null && ACTION_START.equals(intent.getAction())) {
            if (!isRecording) {
                stopping = false;
                startForeground(NOTIFICATION_ID, notification("Recording memory"));
                prepareCamera();
            }
        }
        return Service.START_STICKY;
    }

    private void prepareCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();
                QualitySelector quality = QualitySelector.from(Quality.FHD);
                recorder = new Recorder.Builder().setQualitySelector(quality).build();
                videoCapture = VideoCapture.withOutput(recorder);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture);
                startSegment();
            } catch (Exception e) {
                isRecording = false;
                stopSelf();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startSegment() {
        if (stopping || videoCapture == null) return;
        File dir = new File(getExternalFilesDir(null), "Memory");
        if (!dir.exists()) dir.mkdirs();

        currentFile = new File(dir, "MEMORY_" + System.currentTimeMillis() + ".mp4");
        currentSegmentName = currentFile.getName();

        FileOutputOptions output = new FileOutputOptions.Builder(currentFile).build();
        recording = recorder.prepareRecording(this, output)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                        if (!stopping && finalize.getError() == VideoRecordEvent.Finalize.ERROR_NONE) {
                            handler.postDelayed(this::startSegment, 250);
                        }
                    }
                });

        isRecording = true;
        handler.postDelayed(() -> {
            if (!stopping && recording != null) {
                Recording r = recording;
                recording = null;
                r.stop();
            }
        }, SEGMENT_MS);
    }

    private void stopCurrentRecording() {
        handler.removeCallbacksAndMessages(null);
        if (recording != null) {
            Recording r = recording;
            recording = null;
            r.stop();
        }
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("MA CAMARA")
                .setContentText(text + " • tap to return to app")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "MA CAMARA recording", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        stopping = true;
        isRecording = false;
        stopCurrentRecording();
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }
}
