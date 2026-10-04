package com.macamara.app;
import android.content.*;import android.graphics.Bitmap;import android.media.MediaMetadataRetriever;import com.google.mlkit.common.model.LocalModel;import com.google.mlkit.vision.common.InputImage;import com.google.mlkit.vision.objects.*;import com.google.mlkit.vision.text.*;import java.io.File;
public class ClipAnalyzer{
 public static void analyze(Context ctx,long clipId,File file,MemoryStore store){
  new Thread(()->{MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());String dur=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);long ms=dur==null?0:Long.parseLong(dur);long at=Math.max(0,ms/2);Bitmap b=r.getFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(b==null)return;
   InputImage image=InputImage.fromBitmap(b,0);
   ObjectDetectorOptions oo=new ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().enableClassification().build();
   ObjectDetector od=ObjectDetection.getClient(oo);
   TextRecognizer tr=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
   final String[] vehicle={""}; final String[] plate={""}; final float[] conf={0};
   od.process(image).addOnSuccessListener(list->{for(DetectedObject o:list)for(DetectedObject.Label l:o.getLabels()){if(l.getIndex()==2||l.getText().toLowerCase().contains("car")||l.getText().toLowerCase().contains("vehicle")||l.getText().toLowerCase().contains("truck")||l.getText().toLowerCase().contains("bus")||l.getText().toLowerCase().contains("motorcycle")){vehicle[0]=l.getText();conf[0]=Math.max(conf[0],l.getConfidence());}}}).addOnCompleteListener(x->{tr.process(image).addOnSuccessListener(t->{String raw=t.getText().replace("\n"," ");if(raw.length()>2)plate[0]=raw;}).addOnCompleteListener(y->{store.addAnalysis(clipId,vehicle[0],plate[0],conf[0]);od.close();tr.close();});});
  }catch(Exception ignored){}finally{r.release();}}).start();
 }
}