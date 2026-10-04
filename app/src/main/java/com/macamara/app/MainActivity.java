package com.macamara.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends ComponentActivity {
    private static final int REQUEST_PERMISSIONS = 40;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, timer;
    private Button startStop, remember;
    private long startedAt = 0L;

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (startedAt > 0) {
                long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
                timer.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d",
                        seconds / 3600, (seconds / 60) % 60, seconds % 60));
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        timer = findViewById(R.id.timer);
        startStop = findViewById(R.id.startStop);
        remember = findViewById(R.id.remember);
        Button timeline = findViewById(R.id.timeline);

        startStop.setOnClickListener(v -> {
            if (RecordingService.isRecording) stopMemory();
            else requestAndStart();
        });
        remember.setOnClickListener(v -> rememberIncident());
        timeline.setOnClickListener(v -> showTimeline());

        if (RecordingService.isRecording) enterRecordingUi();
    }

    private void requestAndStart() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
        };
        boolean missing = false;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing = true; break;
            }
        }
        if (missing) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        } else startMemory();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) startMemory();
            else status.setText("Camera, microphone and notification permissions are required.");
        }
    }

    private void startMemory() {
        ContextCompat.startForegroundService(this, new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_START));
        enterRecordingUi();
    }

    private void stopMemory() {
        startService(new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_STOP));
        RecordingService.isRecording = false;
        startedAt = 0;
        handler.removeCallbacks(clock);
        status.setText("Memory saved locally");
        startStop.setText("START MEMORY");
        remember.setEnabled(false);
        timer.setText("00:00:00");
    }

    private void enterRecordingUi() {
        RecordingService.isRecording = true;
        startedAt = System.currentTimeMillis();
        status.setText("● RECORDING — visible memory capture active");
        startStop.setText("STOP & SAVE MEMORY");
        remember.setEnabled(true);
        handler.removeCallbacks(clock);
        handler.post(clock);
    }

    private void rememberIncident() {
        try {
            File marker = new File(getExternalFilesDir(null), "Memory/REMEMBERED_" +
                    System.currentTimeMillis() + ".txt");
            File parent = marker.getParentFile();
            if (parent != null) parent.mkdirs();
            java.nio.file.Files.writeString(marker.toPath(),
                    "Incident remembered at " + new java.util.Date() +
                    "\nCurrent recording segment: " + RecordingService.currentSegmentName);
            status.setText("Incident bookmarked in memory");
        } catch (Exception e) {
            status.setText("Could not create incident bookmark");
        }
    }

    private void showTimeline() {
        File dir = new File(getExternalFilesDir(null), "Memory");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(this).setTitle("Memory Timeline")
                    .setMessage("No saved clips yet. Start Memory to create your first video timeline.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        StringBuilder text = new StringBuilder();
        int count = 0;
        for (File f : files) {
            if (count++ >= 20) break;
            text.append("• ").append(f.getName()).append("\n");
        }
        new AlertDialog.Builder(this).setTitle("Memory Timeline")
                .setMessage(text.toString())
                .setPositiveButton("OK", null).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(clock);
    }
}
