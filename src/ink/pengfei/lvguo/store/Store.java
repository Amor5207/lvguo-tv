package ink.pengfei.lvguo.store;

import ink.pengfei.lvguo.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 收藏 / 播放历史的本地镜像：与红果账号云端同步（见 LibrarySync），本地只做缓存，
 * 起播和列表零网络等待；没有条数上限，增删改经 Sync 钩子写回云端。
 */
public final class Store {
    // 收藏 / 历史都与云端同步，不再做本地条数上限（旧的 200 / 300 条限制只在纯本地存储时需要）
    public static final int HISTORY_LIMIT=100000, FAVORITE_LIMIT=100000;
    private static void trim(JSONArray rows,int limit){while(rows.length()>limit)rows.remove(0);}
    public static synchronized boolean completed(String id){
        for(String key:new String[]{"fav","history"}){JSONArray rows=arr(key);for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row!=null && id.equals(row.optString("id")) && row.optInt("completed_total")>0){
                ink.pengfei.lvguo.net.Repo.Detail detail=ink.pengfei.lvguo.net.Repo.cachedDetail(id);
                if(detail==null || detail.episodes.size()<=row.optInt("completed_total"))return true;
            }
        }}return false;
    }
    /** 一次扫描得到所有已看完的剧 id；逐条调 completed() 在大历史上是二次方。 */
    public static synchronized java.util.Set<String> completedIds(){
        java.util.Set<String> out=new java.util.HashSet<>();
        for(String key:new String[]{"fav","history"}){JSONArray rows=arr(key);for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null || row.optInt("completed_total")<=0)continue;
            String id=row.optString("id");if(out.contains(id))continue;
            ink.pengfei.lvguo.net.Repo.Detail detail=ink.pengfei.lvguo.net.Repo.cachedDetail(id);
            if(detail==null || detail.episodes.size()<=row.optInt("completed_total"))out.add(id);
        }}return out;
    }
    public static synchronized void markCompleted(String id,int total){
        if(id==null || total<=0)return;
        for(String key:new String[]{"fav","history"}){JSONArray rows=arr(key);for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row!=null && id.equals(row.optString("id")))try{row.put("completed_total",total);}catch(Exception ignored){}
        }}flush();
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
        /** Last-watch timestamp from read_progress/get; distinct from shelf collection time. */
        public long progressTime;
        public int positionMs,durationMs,episodeCount;
        /** App field player_accumulate_total_time; never substitute the current position. */
        public long watchedMs;
    }

    private static final Object LOCK = new Object();
    private static JSONObject sData;

    /**
     * 云同步钩子：登录后由 sync.Cloud 注册。本地每次改动（收藏增删、历史删除/清空、进度）
     * 都会回调到这里写回红果云端；云端拉回来的数据走 replaceFromCloud，不会再触发钩子。
     */
    public interface Sync {
        void favorite(String seriesId, boolean add);
        void historyDeleted(List<Item> entries);
        long historyCleared(List<Item> localEntries, long requestedAt);
        void progress(String seriesId, String vid, int episodeIndex, int positionMs, int durationMs);
    }
    private static volatile Sync sync;
    public static void setSync(Sync hook) { sync = hook; }

    /** 数据变化监听（云端拉取覆盖本地后，用于通知可见页面刷新）。 */
    public interface Listener { void onLibraryChanged(); }
    private static final List<Listener> listeners = new ArrayList<>();
    public static void listen(Listener l) { synchronized (listeners) { if (!listeners.contains(l)) listeners.add(l); } }
    public static void unlisten(Listener l) { synchronized (listeners) { listeners.remove(l); } }
    public static void notifyChanged() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> { for (Listener l : copy) try { l.onLibraryChanged(); } catch (RuntimeException ignored) {} });
    }

    public static synchronized void replaceFromCloudIfCurrent(String key, List<ink.pengfei.lvguo.net.Account.Entry> entries, long generation) {
        if (generation == ink.pengfei.lvguo.net.Session.generation()) replaceFromCloud(key, entries);
    }

    /** 用云端数据整表替换本地镜像（新的在前传入）。保留本地已有的封面补全与「已看完」标记。 */
    public static synchronized void replaceFromCloud(String key, List<ink.pengfei.lvguo.net.Account.Entry> entries) {
        JSONArray old = arr(key);
        java.util.Map<String, JSONObject> previous = new java.util.HashMap<>();
        for (int i = 0; i < old.length(); i++) { JSONObject o = old.optJSONObject(i); if (o != null) previous.put(o.optString("id"), o); }
        if ("history".equals(key)) { mergeHistory(entries, previous); return; }
        if ("fav".equals(key)) { mergeFavorites(entries, previous); return; }
        JSONArray rows = new JSONArray();
        for (int i = entries.size() - 1; i >= 0; i--) {          // 存储顺序是旧→新
            ink.pengfei.lvguo.net.Account.Entry e = entries.get(i);
            JSONObject before = previous.get(e.seriesId);
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.seriesId);
                o.put("title", e.title == null || e.title.isEmpty() ? (before == null ? "" : before.optString("title")) : e.title);
                String cover = usableCover(e.cover) ? e.cover : before == null ? "" : before.optString("cover");
                o.put("cover", cover);
                if (e.vid != null) o.put("vid", e.vid); else if (before != null && before.has("vid")) o.put("vid", before.optString("vid"));
                o.put("ep", e.episodeIndex + 1);
                o.put("epIdx", e.episodeIndex);
                o.put("pos", e.positionMs);
                o.put("watched_ms", Math.max(e.watchedMs, before == null ? 0 : before.optLong("watched_ms")));
                o.put("dur", before != null && before.optInt("epIdx", -1) == e.episodeIndex ? before.optInt("dur") : e.durationMs);
                o.put("ts", e.time > 0 ? e.time : System.currentTimeMillis());
                if (e.episodeCount > 0) o.put("count", e.episodeCount);
                if (before != null && before.optInt("completed_total") > 0) o.put("completed_total", before.optInt("completed_total"));
                rows.put(o);
            } catch (Exception ignored) { }
        }
        try { data().put(key, rows); } catch (Exception ignored) { }
        flush();
    }

    /** Keep unconfirmed local favorite changes visible while the cloud list catches up. */
    private static void mergeFavorites(List<ink.pengfei.lvguo.net.Account.Entry> cloud, java.util.Map<String, JSONObject> previous) {
        java.util.Map<String, JSONObject> merged = new java.util.LinkedHashMap<>();
        for (int i = cloud.size() - 1; i >= 0; i--) {
            ink.pengfei.lvguo.net.Account.Entry e = cloud.get(i);
            JSONObject before = previous.get(e.seriesId);
            try {
                JSONObject row = new JSONObject();
                row.put("id", e.seriesId);
                row.put("title", e.title == null || e.title.isEmpty() ? (before == null ? "" : before.optString("title")) : e.title);
                row.put("cover", usableCover(e.cover) ? e.cover : before == null ? "" : before.optString("cover"));
                boolean freshProgress = e.progressTime > 0;
                boolean keptProgress = before != null && before.optLong("progress_ts", 0) > 0;
                if (freshProgress && e.vid != null) row.put("vid", e.vid);
                else if (keptProgress && before.has("vid")) row.put("vid", before.optString("vid"));
                else if (e.vid != null) row.put("vid", e.vid);
                else if (before != null && before.has("vid")) row.put("vid", before.optString("vid"));
                int episodeIndex = freshProgress ? e.episodeIndex
                        : keptProgress ? before.optInt("epIdx", e.episodeIndex) : e.episodeIndex;
                row.put("ep", episodeIndex + 1); row.put("epIdx", episodeIndex);
                row.put("ts", e.time > 0 ? e.time : System.currentTimeMillis());
                if (e.episodeCount > 0) row.put("count", e.episodeCount);
                if (freshProgress) {
                    row.put("pos", e.positionMs); row.put("dur", e.durationMs);
                    row.put("watched_ms", e.watchedMs); row.put("progress_ts", e.progressTime);
                } else if (keptProgress) {
                    row.put("pos", before.optInt("pos", 0)); row.put("dur", before.optInt("dur", 0));
                    row.put("watched_ms", before.optLong("watched_ms", 0));
                    row.put("progress_ts", before.optLong("progress_ts", 0));
                }
                if (before != null && before.optInt("completed_total") > 0) row.put("completed_total", before.optInt("completed_total"));
                merged.put(e.seriesId, row);
            } catch (Exception ignored) { }
        }
        JSONObject pending = data().optJSONObject("fav_pending");
        if (pending != null) for (java.util.Iterator<String> it = pending.keys(); it.hasNext();) {
            String id = it.next();
            if (!pending.optBoolean(id)) { merged.remove(id); continue; }
            if (merged.containsKey(id)) continue;
            JSONObject before = previous.get(id);
            try {
                JSONObject row = before == null ? new JSONObject() : new JSONObject(before.toString());
                row.put("id", id);
                if (!row.has("ts")) row.put("ts", System.currentTimeMillis());
                merged.put(id, row);
            } catch (Exception ignored) { }
        }
        List<JSONObject> sorted = new ArrayList<>(merged.values());
        java.util.Collections.sort(sorted, (a, b) -> Long.compare(a.optLong("ts"), b.optLong("ts")));
        JSONArray rows = new JSONArray(); for (JSONObject row : sorted) rows.put(row);
        trim(rows, FAVORITE_LIMIT);
        try { data().put("fav", rows); } catch (Exception ignored) { }
        flush();
    }

    /** Discard the previous account's local library without syncing deletions. */
    public static synchronized void clearAccountData() {
        for (String key : new String[]{"fav", "like", "history", "fav_pending"}) data().remove(key);
        flush();
        notifyChanged();
    }

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

    /**
     * 观看历史合并：云端（手机同源）为准，但本地刚看过、比云端更新的记录不能被旧数据盖掉
     * （云端历史有延迟；之前就是这样导致「看了几十集，列表还显示第 1 集」）。
     * 规则：同一部剧取更新时间较新的那条；本地有、云端还没有且比云端最新一条还新的，也保留。
     */
    private static void mergeHistory(List<ink.pengfei.lvguo.net.Account.Entry> cloud, java.util.Map<String, JSONObject> local) {
        java.util.Map<String, JSONObject> merged = new java.util.LinkedHashMap<>();
        long newestCloud = 0;
        for (ink.pengfei.lvguo.net.Account.Entry e : cloud) {
            newestCloud = Math.max(newestCloud, e.time);
            JSONObject before = local.get(e.seriesId);
            if (before != null && before.optBoolean("pending_sync")) {
                try {
                    before.put("cloud_seen", true);
                    if (sameHistoryEpisode(e, before))
                        before.put("watched_ms", Math.max(e.watchedMs, before.optLong("watched_ms", 0)));
                } catch (Exception ignored) { }
            }
            if (before != null && HistoryConflict.keepPending(before.optBoolean("pending_sync"), before.optLong("ts", 0), e.time)) {
                merged.put(e.seriesId, before); continue;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.seriesId);
                o.put("cloud_seen", true);
                o.put("title", e.title == null || e.title.isEmpty() ? (before == null ? "" : before.optString("title")) : e.title);
                o.put("cover", usableCover(e.cover) ? e.cover : before == null ? "" : before.optString("cover"));
                if (e.vid != null) o.put("vid", e.vid); else if (before != null && before.has("vid")) o.put("vid", before.optString("vid"));
                o.put("ep", e.episodeIndex + 1);
                o.put("epIdx", e.episodeIndex);
                o.put("pos", e.positionMs);
                o.put("watched_ms", sameHistoryEpisode(e, before)
                        ? Math.max(e.watchedMs, before.optLong("watched_ms", 0)) : Math.max(0, e.watchedMs));
                o.put("dur", before != null && before.optInt("epIdx", -1) == e.episodeIndex ? before.optInt("dur") : e.durationMs);
                o.put("ts", e.time > 0 ? e.time : System.currentTimeMillis());
                if (e.episodeCount > 0) o.put("count", e.episodeCount); else if (before != null && before.optInt("count") > 0) o.put("count", before.optInt("count"));
                if (before != null && before.optInt("completed_total") > 0) o.put("completed_total", before.optInt("completed_total"));
                merged.put(e.seriesId, o);
            } catch (Exception ignored) { }
        }
        for (JSONObject o : local.values()) {
            String id = o.optString("id");
            if (!merged.containsKey(id) && o.optBoolean("pending_sync")) merged.put(id, o);
        }
        List<JSONObject> rows = new ArrayList<>(merged.values());
        java.util.Collections.sort(rows, (x, y) -> Long.compare(x.optLong("ts"), y.optLong("ts")));   // 存储顺序：旧 → 新
        JSONArray out = new JSONArray();
        for (JSONObject o : rows) out.put(o);
        trim(out, HISTORY_LIMIT);
        try { data().put("history", out); } catch (Exception ignored) { }
        flush();
    }

    private static boolean sameHistoryEpisode(ink.pengfei.lvguo.net.Account.Entry entry, JSONObject local) {
        if (local == null || local.optInt("epIdx", -1) != entry.episodeIndex) return false;
        String localVid = local.optString("vid", "");
        return entry.vid == null || entry.vid.isEmpty() || entry.vid.equals(localVid);
    }

    public static synchronized boolean isIn(String key, String seriesId) {
        JSONArray a = arr(key);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("id"))) return true;
        }
        return false;
    }

    public static synchronized void put(String key, String seriesId, String title, String cover, String vid) {
        putInternal(key, seriesId, title, cover, vid, true);
    }

    private static void putInternal(String key, String seriesId, String title, String cover, String vid, boolean syncFavorite) {
        if (seriesId == null) return;
        boolean favorite = "fav".equals(key) && syncFavorite;
        if (favorite && ink.pengfei.lvguo.net.Session.loggedIn()) favoriteIntent(seriesId, true);
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
                if (favorite && sync != null) sync.favorite(seriesId, true);
                return;
            }
        }
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
                    ink.pengfei.lvguo.net.Repo.Detail detail=ink.pengfei.lvguo.net.Repo.cachedDetail(seriesId);
                    if(detail!=null)o.put("completed_total",detail.episodes.size());
                }
            }
            a.put(o);
            flush();
            if (favorite && sync != null) sync.favorite(seriesId, true);
        } catch (Throwable ignored) {
        }
    }

    /** 批量移出收藏：整库只落盘一次（逐条 remove 每条要写两遍 library.json）。 */
    public static synchronized void removeFavorites(java.util.Collection<String> ids) {
        boolean loggedIn = ink.pengfei.lvguo.net.Session.loggedIn();
        JSONArray a = arr("fav");
        JSONObject pending = data().optJSONObject("fav_pending");
        if (pending == null) pending = new JSONObject();
        List<String> removedIds = new ArrayList<>();
        try {
            for (String id : ids) {
                if (id == null) continue;
                if (loggedIn) pending.put(id, false);
                boolean removed = false;
                for (int i = a.length() - 1; i >= 0; i--) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && id.equals(o.optString("id"))) { a.remove(i); removed = true; }
                }
                if (removed) removedIds.add(id);
            }
            if (loggedIn) data().put("fav_pending", pending);
        } catch (Exception ignored) { }
        flush();
        Sync hook = sync;
        if (hook != null) for (String id : removedIds) hook.favorite(id, false);
    }

    public static synchronized void remove(String key, String seriesId) {
        boolean favorite = "fav".equals(key);
        if (favorite && ink.pengfei.lvguo.net.Session.loggedIn()) favoriteIntent(seriesId, false);
        if ("history".equals(key) && sync != null && ink.pengfei.lvguo.net.Session.loggedIn() && isIn(key, seriesId)) {
            Item entry = watched(seriesId);
            if (entry != null) sync.historyDeleted(java.util.Collections.singletonList(entry));
            return;
        }
        JSONArray a = arr(key);
        boolean removed = false;
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && seriesId.equals(o.optString("id"))) { a.remove(i); removed = true; }
        }
        flush();
        Sync hook = sync;
        if (hook != null && removed) {
            if ("fav".equals(key)) hook.favorite(seriesId, false);
            else if ("history".equals(key)) {
                Item entry = watched(seriesId);
                if (entry != null) hook.historyDeleted(java.util.Collections.singletonList(entry));
            }
        }
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

    public static synchronized long clearHistory(){
        List<Item> entries = history();
        List<String> ids = new ArrayList<>();
        for (Item item : entries) ids.add(item.seriesId);
        Sync hook=sync;
        if(hook!=null && ink.pengfei.lvguo.net.Session.loggedIn()) return hook.historyCleared(entries, System.currentTimeMillis());
        applyHistoryDeletion(ids, Long.MAX_VALUE);
        return 0;
    }

    /** Called only after the cloud confirms deletion; never invokes the sync hook again. */
    public static synchronized void applyHistoryDeletionIfCurrent(List<String> ids, long requestedAt, long generation) {
        if (generation == ink.pengfei.lvguo.net.Session.generation()) applyHistoryDeletion(ids, requestedAt);
    }
    public static synchronized void applyCloudHistoryDeletionsIfCurrent(java.util.Map<String, Long> deleted, long generation) {
        if (generation != ink.pengfei.lvguo.net.Session.generation() || deleted.isEmpty()) return;
        JSONArray rows = arr("history");
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Long when = deleted.get(row.optString("id"));
            if (when != null && HistoryConflict.deletedAfterLocal(row.optLong("ts", 0), when)) rows.remove(i);
        }
        flush();
    }
    public static synchronized void applyHistoryDeletion(List<String> ids, long requestedAt) {
        java.util.Set<String> deleted = new java.util.HashSet<>(ids);
        JSONArray rows = arr("history");
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && deleted.contains(row.optString("id")) && row.optLong("ts", 0) <= requestedAt) rows.remove(i);
        }
        flush(); notifyChanged();
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
            if(keep.optInt("epIdx",-1)!=episodeIndex){keep.put("pos",0);keep.put("dur",0);keep.put("watched_ms",0);}
            // Every playback start is a new local intent, even when resuming the same
            // episode. An older successful snapshot must not acknowledge this one.
            clearSubmissionMarkers(keep);
            keep.put("ep", episode);
            keep.put("epIdx", episodeIndex);
            if (vid != null) {
                keep.remove("vid");
                keep.put("vid", vid);
            }
            keep.put("ts", System.currentTimeMillis());
            // Keep a just-started episode safe from an older cloud snapshot arriving
            // before the first position checkpoint. Store.progress() will enqueue the
            // actual upload once the player reports its position.
            keep.put("pending_sync", true);
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
            it.progressTime = o.optLong("progress_ts", 0);
            it.positionMs=o.optInt("pos");it.durationMs=o.optInt("dur");it.episodeCount=o.optInt("count");
            it.watchedMs=o.optLong("watched_ms", 0);
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
        else if (isIn("fav", id)) remove("fav",id);
        else {
            if (ink.pengfei.lvguo.net.Session.loggedIn()) favoriteIntent(id, false);
            if (sync != null) sync.favorite(id, false);
        }
    }

    private static void favoriteIntent(String id, boolean enabled) {
        if (id == null) return;
        JSONObject pending = data().optJSONObject("fav_pending");
        if (pending == null) pending = new JSONObject();
        try { pending.put(id, enabled); data().put("fav_pending", pending); flush(); } catch (Exception ignored) { }
    }

    public static final class FavoriteChange {
        public final String seriesId; public final boolean add;
        private FavoriteChange(String id, boolean add) { this.seriesId = id; this.add = add; }
    }

    public static synchronized List<FavoriteChange> pendingFavorites() {
        List<FavoriteChange> result = new ArrayList<>();
        JSONObject pending = data().optJSONObject("fav_pending");
        if (pending != null) for (java.util.Iterator<String> it = pending.keys(); it.hasNext();) {
            String id = it.next(); result.add(new FavoriteChange(id, pending.optBoolean(id)));
        }
        return result;
    }

    public static synchronized boolean favoriteIntentIsCurrent(String id, boolean add, long generation) {
        if (generation != ink.pengfei.lvguo.net.Session.generation()) return false;
        JSONObject pending = data().optJSONObject("fav_pending");
        return pending != null && pending.has(id) && pending.optBoolean(id) == add;
    }

    public static synchronized void favoriteSyncedIfCurrent(String id, boolean add, long generation) {
        if (!favoriteIntentIsCurrent(id, add, generation)) return;
        JSONObject pending = data().optJSONObject("fav_pending");
        if (pending == null) return;
        pending.remove(id);
        if (pending.length() == 0) data().remove("fav_pending");
        flush();
    }
    public static synchronized void progress(String id,int episodeIndex,int position,int duration){
        JSONArray rows=arr("history");
        for(int n=rows.length()-1;n>=0;n--){JSONObject row=rows.optJSONObject(n);
            if(row!=null && id.equals(row.optString("id")) && row.optInt("epIdx",-1)==episodeIndex){
                if(row.optInt("pos",-1)!=Math.max(0,position)) clearSubmissionMarkers(row);
                try{row.put("pos",Math.max(0,position));row.put("dur",Math.max(0,duration));row.put("ts",System.currentTimeMillis());row.put("pending_sync",true);}catch(Exception ignored){}
                App.io().execute(Store::flush);
                Sync hook=sync;if(hook!=null)hook.progress(id,row.optString("vid",null),episodeIndex,position,duration);
                return;
            }
        }
    }
    /** Pending local playback survives network failures and process restarts. */
    public static synchronized List<Item> pendingProgress() {
        List<Item> pending = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.optBoolean("pending_sync")) ids.add(row.optString("id"));
        }
        if (ids.isEmpty()) return pending;
        for (Item item : list("history")) if (ids.contains(item.seriesId)) pending.add(item);
        return pending;
    }

    /** Unknown ids can produce synthetic deletion markers; query only records seen in cloud. */
    public static synchronized List<String> pendingIdsSeenInCloud() {
        List<String> ids = new ArrayList<>();
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && HistoryConflict.queryDeletion(row.optBoolean("pending_sync"), row.optBoolean("cloud_seen")))
                ids.add(row.optString("id"));
        }
        return ids;
    }

    public static synchronized void progressSynced(String id, int episode, int position, long generation) {
        if (generation != ink.pengfei.lvguo.net.Session.generation()) return;
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id")) && row.optInt("epIdx", -1) == episode
                    && row.optInt("pos", -1) == position) { row.remove("pending_sync"); clearSubmissionMarkers(row); flush(); return; }
        }
    }

    /** Confirm accepted snapshots against the complete cloud list, not only its first page. */
    public static synchronized void confirmCloudProgressIfCurrent(List<ink.pengfei.lvguo.net.Account.Entry> entries, long generation) {
        if (generation != ink.pengfei.lvguo.net.Session.generation()) return;
        java.util.Map<String, ink.pengfei.lvguo.net.Account.Entry> byId = new java.util.HashMap<>();
        for (ink.pengfei.lvguo.net.Account.Entry entry : entries) {
            ink.pengfei.lvguo.net.Account.Entry previous = byId.get(entry.seriesId);
            if (previous == null || entry.time > previous.time) byId.put(entry.seriesId, entry);
        }
        JSONArray rows = arr("history"); boolean changed = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !row.optBoolean("pending_sync")) continue;
            ink.pengfei.lvguo.net.Account.Entry entry = byId.get(row.optString("id"));
            if (entry == null) continue;
            int episode = row.optInt("epIdx", -1), position = row.optInt("pos", -1);
            String vid = row.optString("vid", "");
            // History and progress are separate APK RPCs. Do not acknowledge the local
            // snapshot until the progress route itself has accepted it as well.
            if (progressRateSubmittedAt(row.optString("id"), vid, episode, position, generation) == 0) continue;
            if (row.optInt("submitted_episode", -1) != episode || row.optInt("submitted_pos", -1) != position
                    || !vid.equals(row.optString("submitted_vid", ""))) continue;
            if (HistoryConflict.confirmsProgress(row.optLong("submitted_at", 0), episode, position, vid,
                    entry.time, entry.episodeIndex, entry.positionMs, entry.vid)) {
                row.remove("pending_sync"); clearSubmissionMarkers(row); changed = true;
            }
        }
        if (changed) flush();
    }

    /** Server accepted this exact progress snapshot; cloud history may appear later. */
    public static synchronized void progressSubmitted(String id, String vid, int episode, int position, long time, long generation) {
        if (!pendingProgressIsCurrent(id, vid, episode, position, generation)) return;
        JSONArray rows = arr("history");
        for (int i=0; i<rows.length(); i++) {
            JSONObject row=rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) {
                try { row.put("submitted_at", time); row.put("submitted_pos", position); row.put("submitted_episode", episode); row.put("submitted_vid", vid); flush(); }
                catch (Exception ignored) { }
                return;
            }
        }
    }
    public static synchronized long progressSubmittedAt(String id, String vid, int episode, int position, long generation) {
        if (!pendingProgressIsCurrent(id, vid, episode, position, generation)) return 0;
        JSONArray rows = arr("history");
        for (int i=0; i<rows.length(); i++) {
            JSONObject row=rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id")) && row.optInt("submitted_episode", -1)==episode
                    && row.optInt("submitted_pos", -1)==position && vid.equals(row.optString("submitted_vid")))
                return row.optLong("submitted_at", 0);
        }
        return 0;
    }

    /** Mark the APK's separate read_progress/upload request as accepted for this snapshot. */
    public static synchronized void progressRateSubmitted(String id, String vid, int episode, int position,
                                                           long time, long generation) {
        if (!pendingProgressIsCurrent(id, vid, episode, position, generation)) return;
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) {
                try {
                    row.put("split_progress_uploads", true);
                    row.put("rate_submitted_at", time); row.put("rate_submitted_pos", position);
                    row.put("rate_submitted_episode", episode); row.put("rate_submitted_vid", vid);
                    flush();
                } catch (Exception ignored) { }
                return;
            }
        }
    }

    /** Start tracking history and progress RPC acknowledgements independently. */
    public static synchronized void splitProgressSubmissions(String id, String vid, int episode,
                                                              int position, long generation) {
        if (!pendingProgressIsCurrent(id, vid, episode, position, generation)) return;
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) {
                try { row.put("split_progress_uploads", true); flush(); }
                catch (Exception ignored) { }
                return;
            }
        }
    }

    /** Return 0 for a failed/missing progress RPC; old combined acknowledgements remain valid. */
    public static synchronized long progressRateSubmittedAt(String id, String vid, int episode,
                                                              int position, long generation) {
        if (!pendingProgressIsCurrent(id, vid, episode, position, generation)) return 0;
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !id.equals(row.optString("id"))) continue;
            if (row.optInt("rate_submitted_episode", -1) == episode
                    && row.optInt("rate_submitted_pos", -1) == position
                    && vid.equals(row.optString("rate_submitted_vid", "")))
                return row.optLong("rate_submitted_at", 0);
            // Before split tracking was introduced, submitted_at was recorded only after
            // both upload routes had succeeded, so it is a valid legacy acknowledgement.
            if (!row.optBoolean("split_progress_uploads")
                    && row.optInt("submitted_episode", -1) == episode
                    && row.optInt("submitted_pos", -1) == position
                    && vid.equals(row.optString("submitted_vid", "")))
                return row.optLong("submitted_at", 0);
        }
        return 0;
    }

    /**
     * 一次性：清掉尚未被云端确认的「观看历史已提交」标记，让这些记录重新上报。
     * 旧版本用的签名会被服务端静默丢弃，当时记下的「已提交」并不成立。
     */
    public static synchronized void resendUnconfirmedHistoryOnce() {
        android.content.SharedPreferences prefs = ink.pengfei.lvguo.App.prefs();
        if (prefs.getInt("history_resend_version", 0) >= 2) return;
        JSONArray rows = arr("history"); boolean changed = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !row.optBoolean("pending_sync") || !row.has("submitted_at")) continue;
            row.remove("submitted_at"); row.remove("submitted_pos");
            row.remove("submitted_episode"); row.remove("submitted_vid");
            changed = true;
        }
        if (changed) flush();
        prefs.edit().putInt("history_resend_version", 2).apply();
    }

    private static void clearSubmissionMarkers(JSONObject row) {
        row.remove("submitted_at"); row.remove("submitted_pos");
        row.remove("submitted_episode"); row.remove("submitted_vid");
        row.remove("rate_submitted_at"); row.remove("rate_submitted_pos");
        row.remove("rate_submitted_episode"); row.remove("rate_submitted_vid");
        row.remove("split_progress_uploads");
    }

    /** Avoid sending a queued snapshot after newer playback or an account change. */
    public static synchronized boolean pendingProgressIsCurrent(String id, String vid, int episode, int position, long generation) {
        if (generation != ink.pengfei.lvguo.net.Session.generation()) return false;
        JSONArray rows = arr("history");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id"))) {
                return row.optBoolean("pending_sync") && vid.equals(row.optString("vid"))
                        && row.optInt("epIdx", -1) == episode && (position < 0 || row.optInt("pos", -1) == position);
            }
        }
        return false;
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
