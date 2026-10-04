package com.macamara.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public class MemoryStore extends SQLiteOpenHelper {
    private static final int DB_VERSION=2;

    public MemoryStore(Context c){super(c,"memory.db",null,DB_VERSION);}

    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT,path TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,vehicle TEXT,plate TEXT,confidence REAL DEFAULT 0,incident INTEGER DEFAULT 0)");
        d.execSQL("CREATE TABLE pending_incidents(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX idx_clips_start ON clips(start)");
        d.execSQL("CREATE INDEX idx_clips_vehicle ON clips(vehicle)");
        d.execSQL("CREATE INDEX idx_clips_plate ON clips(plate)");
        d.execSQL("CREATE INDEX idx_pending_time ON pending_incidents(time)");
    }

    @Override public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion){
        if(oldVersion<2){
            d.execSQL("CREATE TABLE IF NOT EXISTS pending_incidents(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL)");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_clips_start ON clips(start)");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_clips_vehicle ON clips(vehicle)");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_clips_plate ON clips(plate)");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_time ON pending_incidents(time)");
        }
    }

    public synchronized long addClip(String path,long start,long end){
        SQLiteDatabase d=getWritableDatabase();
        ContentValues v=new ContentValues();
        v.put("path",path); v.put("start",start); v.put("end",end);
        long id=d.insert("clips",null,v);

        Cursor c=d.rawQuery("SELECT id,time FROM pending_incidents WHERE time BETWEEN ? AND ?",
            new String[]{String.valueOf(start),String.valueOf(end)});
        try{
            while(c.moveToNext()){
                ContentValues incident=new ContentValues();
                incident.put("incident",1);
                d.update("clips",incident,"id=?",new String[]{String.valueOf(id)});
                d.delete("pending_incidents","id=?",new String[]{String.valueOf(c.getLong(0))});
            }
        }finally{c.close();}
        return id;
    }

    public synchronized void addAnalysis(long id,String vehicle,String plate,float conf){
        ContentValues v=new ContentValues();
        v.put("vehicle",vehicle==null?"":vehicle);
        v.put("plate",plate==null?"":plate);
        v.put("confidence",conf);
        getWritableDatabase().update("clips",v,"id=?",new String[]{String.valueOf(id)});
    }

    public synchronized void incidentNear(long time){
        SQLiteDatabase d=getWritableDatabase();
        ContentValues v=new ContentValues(); v.put("incident",1);
        int updated=d.update("clips",v,"start<=? AND end>=?",
            new String[]{String.valueOf(time),String.valueOf(time)});
        if(updated==0){
            ContentValues pending=new ContentValues(); pending.put("time",time);
            d.insert("pending_incidents",null,pending);
        }
    }

    public synchronized int count(String where){
        String sql="SELECT COUNT(*) FROM clips"+(where==null?"":" WHERE "+where);
        Cursor c=getReadableDatabase().rawQuery(sql,null);
        try{c.moveToFirst();return c.getInt(0);}finally{c.close();}
    }

    public synchronized List<String[]> search(String q){
        List<String[]> out=new ArrayList<>();
        String query=q==null?"":q.trim();
        Cursor c;
        if(query.isEmpty()){
            c=getReadableDatabase().rawQuery(
                "SELECT id,path,start,end,vehicle,plate,confidence,incident FROM clips ORDER BY start DESC LIMIT 80",null);
        }else{
            String s="%"+query+"%";
            c=getReadableDatabase().rawQuery(
                "SELECT id,path,start,end,vehicle,plate,confidence,incident FROM clips WHERE vehicle LIKE ? OR plate LIKE ? OR path LIKE ? ORDER BY start DESC LIMIT 80",
                new String[]{s,s,s});
        }
        try{
            while(c.moveToNext()){
                out.add(new String[]{
                    c.getString(0),c.getString(1),String.valueOf(c.getLong(2)),String.valueOf(c.getLong(3)),
                    c.getString(4),c.getString(5),String.valueOf(c.getFloat(6)),String.valueOf(c.getInt(7))
                });
            }
        }finally{c.close();}
        return out;
    }

    public synchronized List<String[]> recent(){return search("");}

    public synchronized String path(long id){
        Cursor c=getReadableDatabase().rawQuery("SELECT path FROM clips WHERE id=?",
            new String[]{String.valueOf(id)});
        try{return c.moveToFirst()?c.getString(0):null;}finally{c.close();}
    }
}
