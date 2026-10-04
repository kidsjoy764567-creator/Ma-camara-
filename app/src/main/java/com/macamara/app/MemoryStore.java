package com.macamara.app;
import android.content.*;import android.database.Cursor;import android.database.sqlite.*;import java.io.File;import java.util.*;
public class MemoryStore extends SQLiteOpenHelper{
 public MemoryStore(Context c){super(c,"memory.db",null,1);}
 public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT,path TEXT,start INTEGER,end INTEGER,vehicle TEXT,plate TEXT,confidence REAL,incident INTEGER DEFAULT 0)");}
 public void onUpgrade(SQLiteDatabase d,int a,int b){}
 public synchronized long addClip(String path,long start,long end){ContentValues v=new ContentValues();v.put("path",path);v.put("start",start);v.put("end",end);return getWritableDatabase().insert("clips",null,v);}
 public synchronized void addAnalysis(long id,String vehicle,String plate,float conf){ContentValues v=new ContentValues();v.put("vehicle",vehicle);v.put("plate",plate);v.put("confidence",conf);getWritableDatabase().update("clips",v,"id=?",new String[]{""+id});}
 public synchronized void incidentNear(long time){ContentValues v=new ContentValues();v.put("incident",1);getWritableDatabase().update("clips",v,"start<=? AND end>=?",new String[]{""+time,""+time});}
 public synchronized int count(String where){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM clips"+(where==null?"":" WHERE "+where),null);try{c.moveToFirst();return c.getInt(0);}finally{c.close();}}
 public synchronized List<String[]> search(String q){List<String[]> out=new ArrayList<>();String s="%"+q+"%";Cursor c=getReadableDatabase().rawQuery("SELECT id,path,start,end,vehicle,plate,confidence,incident FROM clips WHERE vehicle LIKE ? OR plate LIKE ? OR path LIKE ? OR incident=1 ORDER BY start DESC LIMIT 80",new String[]{s,s,s});try{while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1),""+c.getLong(2),""+c.getLong(3),c.getString(4),c.getString(5),""+c.getFloat(6),""+c.getInt(7)});}finally{c.close();}return out;}
 public synchronized List<String[]> recent(){return search("");}
 public synchronized String path(long id){Cursor c=getReadableDatabase().rawQuery("SELECT path FROM clips WHERE id=?",new String[]{""+id});try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}}
}