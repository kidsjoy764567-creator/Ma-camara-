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
import java.io.IOException;

public class ClipAnalyzer {
 public static void analyze(Context ctx,long clipId,File file,MemoryStore store){
  new Thread(() -> {
   MediaMetadataRetriever r = new MediaMetadataRetriever();
   try {
    r.setDataSource(file.getAbsolutePath());
    String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
    long ms = dur == null ? 0 : Long.parseLong(dur);
    Bitmap b = r.getFrameAtTime(Math.max(0,ms/2)*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
    if(b == null) return;
    InputImage image = InputImage.fromBitmap(b,0);
    ObjectDetectorOptions oo = new ObjectDetectorOptions.Builder()
      .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
      .enableMultipleObjects().enableClassification().build();
    ObjectDetector od = ObjectDetection.getClient(oo);
    TextRecognizer tr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    final String[] vehicle={""}; final String[] plate={""}; final float[] conf={0};
    od.process(image)
      .addOnSuccessListener(list -> {
       for(DetectedObject o:list) for(DetectedObject.Label l:o.getLabels()){
        String label=l.getText().toLowerCase();
        if(label.contains("car")||label.contains("vehicle")||label.contains("truck")||label.contains("bus")||label.contains("motorcycle")||label.contains("scooter")){
         vehicle[0]=l.getText(); conf[0]=Math.max(conf[0],l.getConfidence());
        }
       }
      })
      .addOnCompleteListener(x -> tr.process(image)
       .addOnSuccessListener(t -> {String raw=t.getText().replace("\\n"," "); if(raw.length()>2) plate[0]=raw;})
       .addOnCompleteListener(y -> {
        store.addAnalysis(clipId,vehicle[0],plate[0],conf[0]);
        od.close(); tr.close();
       }));
   } catch (RuntimeException ignored) {
   } finally {
    try { r.release(); } catch (IOException ignored) { }
   }
  }).start();
 }
}