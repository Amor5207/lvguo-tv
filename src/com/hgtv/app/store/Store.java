package com.hgtv.app.store;

import com.hgtv.app.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地库：收藏 / 喜欢 / 播放历史。
 *
 * 不需要登录 —— 书架接口本身也是按设备身份挂的，
 * 所以这几个功能完全本地化，起播和列表都是零网络等待。
 */
public final class Store {
    public static final int HISTORY_LIMIT=200, FAVORITE_LIMIT=300;
    public static final class FavoriteFullException extends IllegalStateException {
        public FavoriteFullException(){super("追剧已满300条，请先清理后再添加");}
    }
    public static boolean autoUnfollow(){return App.prefs().getBoolean("auto_unfollow_completed",true);}
    public static void autoUnfollow(boolean enabled){App.prefs().edit().putBoolean("auto_unfollow_completed",enabled).apply();}
    private static void trim(JSONArray rows,int limit){while(rows.length()>limit)rows.remove(0);}
    public static synchronized boolean completed(String id){
        for(String key:new String[]{"fav","history"}){JSONArray rows=arr(key);for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row!=null && id.equals(row.optString("id")) && row.optInt("completed_total")>0){
                com.hgtv.app.net.Repo.Detail detail=com.hgtv.app.net.Repo.cachedDetail(id);
                if(detail==null || detail.episodes.size()<=row.optInt("completed_total"))return true;
            }
        }}return false;
    }
    public static synchronized void markCompleted(String id,int total){
        if(id==null || total<=0)return;
        for(String key:new String[]{"fav","history"}){JSONArray rows=arr(key);for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row!=null && id.equals(row.optString("id")))try{row.put("completed_total",total);}catch(Exception ignored){}
        }}flush();
    }
    public static synchronized int completedFavorites(){int n=0;for(Item item:favorites())if(completed(item.seriesId))n++;return n;}
    public static synchronized int clearCompletedFavorites(){
        int n=0;JSONArray rows=arr("fav");for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.optJSONObject(i);if(row!=null && completed(row.optString("id"))){rows.remove(i);n++;}}
        if(n>0)flush();return n;
    }


    public static final class Item {
        public String seriesId;
        public String title;
        public String cover;
        public int lastEpisode;
        public int episodeIndex;
        /** 该剧当前可播的 vid（第 1 集或上次看到的集）——详情接口不可用时靠它起播 */
        public String vid;
        public long time;
        public int positionMs,durationMs;
    }

    private static final Object LOCK = new Object();
    private static JSONObject sData;

    private Store() {}

    private static File file() {
        return new File(App.ctx().getFilesDir(), "library.json");
    }

    private static JSONObject data() {
        synchronized (LOCK) {
            if (sData != null) return sData;
            try {
                String s = new String(readAll(file()), "UTF-8");
                sData = new JSONObject(s);
            } catch (Throwable t) {
                sData = new JSONObject();
            }
            return sData;
        }
    }

    private static final Object DISK_LOCK=new Object();
    private static long nextWrite,written;
    private static void flush() {
        final byte[] snapshot;final long serial;
        synchronized(Store.class){if(sData==null)return;try{snapshot=sData.toString().getBytes("UTF-8");serial=++nextWrite;}catch(Exception e){return;}}
        synchronized(DISK_LOCK){
            if(serial<=written)return;
            android.util.AtomicFile file=new android.util.AtomicFile(file());java.io.FileOutputStream out=null;
            try{out=file.startWrite();out.write(snapshot);file.finishWrite(out);written=serial;}catch(Exception error){if(out!=null)file.failWrite(out);}
        }
    }

    private static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new android.util.AtomicFile(f).openRead();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static JSONArray arr(String key) {
        JSONArray a = data().optJSONArray(key);
        if (a == null) {
            a = new JSONArray();
            try { data().put(key, a); } catch (Throwable ignored) {}
        }
        return a;
    }

    // ------------------------------------------------------------ 收藏 / 喜欢

    public static synchronized boolean isIn(String key, String seriesId) {
        JSONArray a = arr(key);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("id"))) return true;
        }
        return false;
    }

    public static synchronized void put(String key, String seriesId, String title, String cover, String vid) {
        if (seriesId == null) return;
        JSONArray a = arr(key);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("id"))) {
                // 老记录也可补齐封面、标题和 vid，不再永远沿用第一次保存的空封面。
                try {
                    boolean dirty = false;
                    if (usableCover(cover) && !cover.equals(o.optString("cover", ""))) {
                        o.put("cover", cover); dirty = true;
                    }
                    if (title != null && !title.isEmpty() && !title.equals(o.optString("title", ""))) {
                        o.put("title", title); dirty = true;
                    }
                    if (vid != null && !vid.equals(o.optString("vid", null))) {
                        o.put("vid", vid); dirty = true;
                    }
                    if (dirty) flush();
                } catch (Throwable ignored) {}
                return;
            }
        }
        if("fav".equals(key) && a.length()>=FAVORITE_LIMIT)throw new FavoriteFullException();
        try {
            JSONObject o = new JSONObject();
            o.put("id", seriesId);
            o.put("title", title == null ? "" : title);
            o.put("cover", cover == null ? "" : cover);
            if (vid != null) o.put("vid", vid);
            o.put("ts", System.currentTimeMillis());
            if("fav".equals(key)){
                Item watched=watched(seriesId);
                if(watched!=null && completed(seriesId)){
                    com.hgtv.app.net.Repo.Detail detail=com.hgtv.app.net.Repo.cachedDetail(seriesId);
                    if(detail!=null)o.put("completed_total",detail.episodes.size());
                }
            }
            a.put(o);
            flush();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void remove(String key, String seriesId) {
        JSONArray a = arr(key);
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("id"))) a.remove(i);
        }
        flush();
    }

    public static synchronized void toggle(String key, String seriesId, String title, String cover, String vid) {
        if (isIn(key, seriesId)) remove(key, seriesId);
        else put(key, seriesId, title, cover, vid);
    }

    public static synchronized boolean isFavorite(String id) { favorites();return id!=null && isIn("fav", id); }
    public static synchronized boolean isLiked(String id)    { return isIn("like", id); }
    public static synchronized void toggleFavorite(String id, String t, String c, String vid) {
        toggle("fav", id, t, c, vid);
    }
    public static synchronized void toggleLike(String id, String t, String c, String vid) {
        toggle("like", id, t, c, vid);
    }

    public static synchronized void clearHistory(){
        try{data().put("history",new JSONArray());flush();}catch(Exception ignored){}
    }
    // ------------------------------------------------------------ 历史

    public static synchronized boolean usableCover(String cover) {
        return cover != null && (cover.startsWith("https://") || cover.startsWith("http://"));
    }

    /** 依据现有收藏、喜欢、历史，补回缺失的封面地址。 */
    public static synchronized String bestCover(String seriesId, String provided) {
        if (usableCover(provided)) return provided;
        if (seriesId == null) return null;
        for (String key : new String[]{"fav", "like", "history"}) {
            JSONArray a = arr(key);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && seriesId.equals(o.optString("id"))) {
                    String c = o.optString("cover", "");
                    if (usableCover(c)) return c;
                }
            }
        }
        return null;
    }

    /** 在剧集详情和播放时对历史数据做无损补全。 */
    public static synchronized void updateCover(String seriesId, String cover) {
        if (seriesId == null || !usableCover(cover)) return;
        for (String key : new String[]{"fav", "like", "history"}) {
            JSONArray a = arr(key);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && seriesId.equals(o.optString("id"))) {
                    try { o.put("cover", cover); } catch (Throwable ignored) {}
                }
            }
        }
        flush();
    }

    public static synchronized void markHistory(String seriesId, String title, int episode,
                                   int episodeIndex, String vid) {
        markHistory(seriesId, title, episode, episodeIndex, vid, null);
    }

    public static synchronized void markHistory(String seriesId, String title, int episode,
                                   int episodeIndex, String vid, String cover) {
        if (seriesId == null) return;
        JSONArray a = arr("history");
        JSONObject keep = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("id"))) {
                keep = o;
                a.remove(i);
                break;
            }
        }
        try {
            if (keep == null) keep = new JSONObject();
            keep.put("id", seriesId);
            keep.put("title", title == null ? "" : title);
            String best = bestCover(seriesId, cover);
            if (usableCover(best)) keep.put("cover", best);
            if(keep.optInt("epIdx",-1)!=episodeIndex){keep.put("pos",0);keep.put("dur",0);}
            keep.put("ep", episode);
            keep.put("epIdx", episodeIndex);
            if (vid != null) {
                keep.remove("vid");
                keep.put("vid", vid);
            }
            keep.put("ts", System.currentTimeMillis());
            a.put(keep);
            trim(a,HISTORY_LIMIT);
            flush();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<Item> list(String key) {
        List<Item> out = new ArrayList<>();
        JSONArray a = arr(key);
        for (int i = a.length() - 1; i >= 0; i--) {     // 新的在前
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.seriesId = o.optString("id");
            it.title = o.optString("title");
            it.cover = bestCover(it.seriesId, o.optString("cover"));
            it.lastEpisode = o.optInt("ep", 0);
            it.episodeIndex = o.optInt("epIdx", 0);
            it.vid = o.optString("vid", null);
            it.time = o.optLong("ts", 0);
            it.positionMs=o.optInt("pos");it.durationMs=o.optInt("dur");
            out.add(it);
        }
        return out;
    }

    public static synchronized Item watched(String id){
        for(Item item:history())if(item.seriesId.equals(id))return item;return null;
    }
    public static synchronized void setFavorite(String id,String title,String cover,String vid,boolean enabled){
        favorites();
        if(enabled)put("fav",id,title,cover,vid);
        else remove("fav",id);
    }
    public static synchronized void progress(String id,int episodeIndex,int position,int duration){
        JSONArray rows=arr("history");
        for(int n=rows.length()-1;n>=0;n--){JSONObject row=rows.optJSONObject(n);
            if(row!=null && id.equals(row.optString("id")) && row.optInt("epIdx",-1)==episodeIndex){
                try{row.put("pos",Math.max(0,position));row.put("dur",Math.max(0,duration));row.put("ts",System.currentTimeMillis());}catch(Exception ignored){}
                App.io().execute(Store::flush);return;
            }
        }
    }
    public static synchronized List<Item> history() { JSONArray rows=arr("history");if(rows.length()>HISTORY_LIMIT){trim(rows,HISTORY_LIMIT);flush();}return list("history"); }
    public static synchronized List<Item> favorites() {
        synchronized(LOCK){
            if(!data().optBoolean("merged_likes_v15")){
                for(Item item:list("like"))if(arr("fav").length()<FAVORITE_LIMIT)put("fav",item.seriesId,item.title,item.cover,item.vid);
                try{data().put("merged_likes_v15",true);}catch(Exception ignored){}flush();
            }
            if(arr("fav").length()>FAVORITE_LIMIT){trim(arr("fav"),FAVORITE_LIMIT);flush();}
            return list("fav");
        }
    }
    public static synchronized List<Item> likes() { return list("like"); }
}
