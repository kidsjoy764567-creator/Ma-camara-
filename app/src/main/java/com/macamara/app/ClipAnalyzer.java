package com.macamara.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClipAnalyzer {
    private static final Pattern PLATE=Pattern.compile(
        "(?i)\b(?:[A-Z]{2}[ -]?[0-9]{1,2}[ -]?[A-Z]{1,3}[ -]?[0-9]{3,4})\b"
    );

    public static void analyze(Context ctx,long clipId,File file,MemoryStore store){
        new Thread(() -> {
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{
                retriever.setDataSource(file.getAbsolutePath());
                String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long ms=duration==null?0:Long.parseLong(duration);
                if(ms<=0) return;

                Bitmap bitmap=retriever.getFrameAtTime(
                    (ms/2)*1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );
                if(bitmap==null) return;

                InputImage image=InputImage.fromBitmap(bitmap,0);
                ObjectDetectorOptions options=new ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
                    .build();
                ObjectDetector detector=ObjectDetection.getClient(options);
                TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

                detector.process(image)
                    .addOnSuccessListener(objects -> {
                        String vehicle="";
                        float confidence=0;
                        for(DetectedObject object:objects){
                            for(DetectedObject.Label label:object.getLabels()){
                                String name=label.getText();
                                String lower=name.toLowerCase(Locale.US);
                                if(lower.contains("vehicle")||lower.contains("car")||lower.contains("truck")
                                    ||lower.contains("bus")||lower.contains("motorcycle")||lower.contains("scooter")){
                                    if(label.getConfidence()>confidence){
                                        vehicle=name;
                                        confidence=label.getConfidence();
                                    }
                                }
                            }
                        }
                        final String detectedVehicle=vehicle;
                        final float detectedConfidence=confidence;

                        recognizer.process(image)
                            .addOnSuccessListener(result -> {
                                String raw=result.getText().replace("\n"," ").trim();
                                Matcher m=PLATE.matcher(raw);
                                String plate="";
                                if(m.find()) plate=m.group().replaceAll("\s+"," ").trim();
                                store.addAnalysis(clipId,detectedVehicle,plate,detectedConfidence);
                            })
                            .addOnFailureListener(e ->
                                store.addAnalysis(clipId,detectedVehicle,"",detectedConfidence)
                            )
                            .addOnCompleteListener(x -> {
                                detector.close();
                                recognizer.close();
                                bitmap.recycle();
                            });
                    })
                    .addOnFailureListener(e -> {
                        recognizer.process(image)
                            .addOnSuccessListener(result -> {
                                Matcher m=PLATE.matcher(result.getText().replace("\n"," "));
                                store.addAnalysis(clipId,"",m.find()?m.group().trim():"",0);
                            })
                            .addOnCompleteListener(x -> {
                                detector.close();
                                recognizer.close();
                                bitmap.recycle();
                            });
                    });
            }catch(Throwable ignored){
                try{ retriever.release(); }catch(Throwable ignoredRelease){}
                return;
            }
            try{ retriever.release(); }catch(Throwable ignoredRelease){}
        }).start();
    }
}
