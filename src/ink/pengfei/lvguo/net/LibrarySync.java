package ink.pengfei.lvguo.net;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.store.Store;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把本地收藏 / 观看历史 / 播放进度与红果云端对齐，保证和手机 App 一致。
 *
 * - 拉取：登录后、回到前台时，从云端读收藏与历史覆盖本地镜像；
 * - 回写：本地增删改经 Store.Sync 钩子，在单独线程顺序写回云端；
 * - 进度：播放时节流（≥10 秒）上报，暂停/切集/退出由播放器直接调 flushProgress。
 */
public final class LibrarySync implements Store.Sync {
    private LibrarySync() {}

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LibrarySync"); t.setDaemon(true); return t;
    });
    private static void executeForSession(long generation, Runnable work) {
        WORK.execute(() -> {
            Account.bindSession(generation);
            try { work.run(); } finally { Account.unbindSession(); }
        });
    }
    private static LibrarySync instance;
    private static volatile long lastProgressUpload;
    /** 播放器每 5 秒存一次位置；这里留一点余量，免得定时抖动把相邻两次合并成 10 秒。 */
    private static final long PROGRESS_THROTTLE_MS = 4_500;
    /** 上传后读回云端历史确认的最小间隔；平时靠定时 pull 对账，不必每次上传都读 500 行。 */
    private static final long CONFIRM_INTERVAL_MS = 5 * 60_000L;
    private static volatile long lastProgressConfirm;
    /** 测试用：让下一次上传重新读回确认。 */
    static void resetProgressConfirmThrottle() { lastProgressConfirm = 0; }
    private static long lastFavoriteProgressPull, favoriteProgressGeneration;
    private static String pendingSeries, pendingVid, pendingTitle;
    private static long pendingGeneration;
    private static int pendingCount;
    private static int pendingIndex, pendingPos, pendingDur;

    /** App 启动时调用：登录则注册钩子并拉一次。 */
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "SessionKeepAlive"); t.setDaemon(true); return t; });
    private static boolean keepAliveStarted;

    private static boolean wasLoggedIn;
    private static long observedGeneration;
    public static synchronized void init() {
        if (instance == null) {
            instance = new LibrarySync(); Store.setSync(instance);
            // 扫码登录成功的那一刻也要拉一次云端收藏/历史（之前只在进程启动时拉，刚登录完首页没有「继续观看」）
            wasLoggedIn = Session.loggedIn();
            observedGeneration = Session.generation();
            Session.listen(() -> {
                boolean now = Session.loggedIn();
                long currentGeneration = Session.generation();
                if (!now && wasLoggedIn) synchronized (LibrarySync.class) {
                    pendingSeries = null; pendingVid = null; pendingTitle = null; lastProgressUpload = 0;
                    lastFavoriteProgressPull = 0; favoriteProgressGeneration = currentGeneration;
                }
                if (now && (!wasLoggedIn || currentGeneration != observedGeneration)) {
                    synchronized (LibrarySync.class) {
                        pendingSeries = null; pendingVid = null; pendingTitle = null; lastProgressUpload = 0;
                        lastFavoriteProgressPull = 0; favoriteProgressGeneration = currentGeneration;
                    }
                    pull(); TIMER.execute(LibrarySync::keepAlive);
                }
                wasLoggedIn = now;
                observedGeneration = currentGeneration;
            });
        }
        if (Session.loggedIn()) pull();
        if (!keepAliveStarted) {
            keepAliveStarted = true;
            // 会话自动续期：启动后 20 秒一次，之后每 6 小时一次。服务端换发的新票由 Session 自动吸收，用户无感知。
            TIMER.scheduleWithFixedDelay(LibrarySync::keepAlive, 5, 6 * 3600, java.util.concurrent.TimeUnit.SECONDS);
            // 每次 pull 都会全量读云端历史/收藏；回到前台和进入「我的」页另有触发，这里只作兜底
            TIMER.scheduleWithFixedDelay(LibrarySync::pull, 5 * 60, 5 * 60, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** 心跳续期 + 刷新资料；会话真的失效时清掉登录态（界面会回到扫码页）。 */
    public static void keepAlive() {
        if (!Session.loggedIn()) return;
        final long generation = Session.generation();
        Account.bindSession(generation);
        try {
        try { if (Account.heartbeat() && generation == Session.generation()) ink.pengfei.lvguo.App.prefs().edit().putLong("last_beat", System.currentTimeMillis()).apply(); }
        catch (Exception e) { android.util.Log.w("LibrarySync", "heartbeat failed: " + e.getMessage()); }
        if (generation != Session.generation()) return;
        try { Account.profile(); }
        catch (Account.AccountException e) { if (e.sessionExpired) Session.expireIfCurrent(generation); }
        catch (Exception e) { log("profile", e); }
        } finally { Account.unbindSession(); }
    }

    /** 回到前台时：距上次续期超过 6 小时就补一次（盒子常年待机，进程里的定时器不一定还在跑）。 */
    public static void keepAliveIfStale() {
        if (!Session.loggedIn()) return;
        long last = ink.pengfei.lvguo.App.prefs().getLong("last_beat", 0);
        if (System.currentTimeMillis() - last > 6 * 3600_000L) TIMER.execute(LibrarySync::keepAlive);
    }

    public interface HistoryReady { void finished(boolean success); }
    private static long startupHistoryGeneration=Long.MIN_VALUE;
    private static int startupHistoryState; // 0 pending, 1 fresh, -1 failed
    private static final java.util.List<HistoryReady> historyWaiters=new java.util.ArrayList<>();

    private static synchronized void ensureStartupHistory(long generation){
        if(startupHistoryGeneration!=generation){
            startupHistoryGeneration=generation;startupHistoryState=0;historyWaiters.clear();
        }
    }
    /** Called during startup; shares the initial sync instead of issuing duplicate requests. */
    public static void preloadHistory(){
        if(!Session.loggedIn())return;
        synchronized(LibrarySync.class){
            ensureStartupHistory(Session.generation());
            if(startupHistoryState!=0)return;
        }
        pull();
    }
    public static synchronized boolean historyReady(){
        return Session.loggedIn() && startupHistoryGeneration==Session.generation() && startupHistoryState==1;
    }
    public static void whenHistoryReady(HistoryReady callback){
        if(!Session.loggedIn()){callback.finished(false);return;}
        int state;
        synchronized(LibrarySync.class){
            ensureStartupHistory(Session.generation());state=startupHistoryState;
            if(state==0)historyWaiters.add(callback);
        }
        if(state!=0)callback.finished(state==1);else pull();
    }
    private static void completeStartupHistory(long generation,boolean success){
        java.util.List<HistoryReady> callbacks;
        synchronized(LibrarySync.class){
            if(generation!=Session.generation() || startupHistoryGeneration!=generation || startupHistoryState!=0)return;
            startupHistoryState=success?1:-1;
            callbacks=new java.util.ArrayList<>(historyWaiters);historyWaiters.clear();
        }
        for(HistoryReady callback:callbacks)try{callback.finished(success);}catch(RuntimeException error){log("history callback",error);}
    }

    /** 从云端覆盖本地收藏与观看历史。 */
    private static final java.util.concurrent.atomic.AtomicBoolean pullQueued = new java.util.concurrent.atomic.AtomicBoolean();
    public static void pull() {
        if (!Session.loggedIn() || !pullQueued.compareAndSet(false, true)) return;
        final long session = Session.generation();
        ensureStartupHistory(session);
        executeForSession(session, () -> {
            try {
            if (session != Session.generation()) return;
            Store.resendUnconfirmedHistoryOnce();
            boolean historyChecked = false;
            try {
                List<Account.Entry> hist = Account.history(Store.HISTORY_LIMIT);
                if (session != Session.generation()) return;
                List<String> pendingIds = Store.pendingIdsSeenInCloud();
                if (!pendingIds.isEmpty()) {
                    try {
                        java.util.Map<String, Long> deleted = Account.deletedHistoryTimes(pendingIds);
                        Store.applyCloudHistoryDeletionsIfCurrent(deleted, session);
                        // The list was fetched before the targeted deletion query; discard
                        // an older list entry if the later query found its deletion marker.
                        java.util.Iterator<Account.Entry> entries = hist.iterator();
                        while (entries.hasNext()) {
                            Account.Entry entry = entries.next();
                            Long time = deleted.get(entry.seriesId);
                            if (time != null && time >= entry.time) entries.remove();
                        }
                    }
                    catch (Exception e) { log("pull history deletions", e); }
                }
                Store.confirmCloudProgressIfCurrent(hist, session);
                Store.replaceFromCloudIfCurrent("history", hist, session);
                historyChecked = true;
            } catch (Exception e) { log("pull history", e); }
            Store.notifyChanged();
            completeStartupHistory(session,historyChecked);
            boolean favoritesChecked = false;
            try {
                List<Account.Entry> fav = Account.favorites();
                if (session != Session.generation()) return;
                if (!fav.isEmpty() && shouldPullFavoriteProgress(session)) {
                    try {
                        List<String> ids = new java.util.ArrayList<>();
                        for (Account.Entry entry : fav) if (entry.seriesId != null) ids.add(entry.seriesId);
                        Account.applyFavoriteProgress(fav, Account.progressByBookIds(ids));
                    } catch (Account.AccountException e) {
                        if (e.sessionExpired) throw e;
                        log("pull favorite progress", e);
                    } catch (Exception e) { log("pull favorite progress", e); }
                }
                if (session != Session.generation()) return;
                Store.replaceFromCloudIfCurrent("fav", fav, session);
                favoritesChecked = true;
            } catch (Exception e) { log("pull favorites", e); }
            if (favoritesChecked) for (Store.FavoriteChange change : Store.pendingFavorites()) {
                if (session != Session.generation()) return;
                try { syncFavorite(change.seriesId, change.add, session); }
                catch (Exception e) { log("pending favorite", e); }
            }
            Store.notifyChanged();
            // Refresh the cloud view before retrying writes, which can each take multiple requests.
            // mergeHistory keeps unsent local progress while applying the rest of the cloud list.
            for (Store.Item item : Store.pendingProgress()) {
                if (session != Session.generation()) return;
                // The complete history fetched above already checked accepted snapshots.
                // Only unsent/failed snapshots need another upload attempt here.
                if (historyChecked && item.vid != null && !item.vid.isEmpty()
                        && Store.progressSubmittedAt(item.seriesId, item.vid, item.episodeIndex,
                                item.positionMs, session) > 0
                        && Store.progressRateSubmittedAt(item.seriesId, item.vid, item.episodeIndex,
                                item.positionMs, session) > 0) continue;
                try { syncProgress(item.seriesId, item.vid, item.title, item.episodeIndex, item.positionMs, item.durationMs, item.episodeCount, session); }
                catch (Exception e) { log("pending progress", e); }
            }
            } finally { completeStartupHistory(session,false);pullQueued.set(false); if (session != Session.generation() && Session.loggedIn()) pull(); }
        });
    }

    /** 立即把当前播放进度写回云端（暂停/切集/退出时）。 */
    public static void flushProgress() {
        final String sid, vid, title; final int idx, pos, dur, count; final long session;
        synchronized (LibrarySync.class) {
            if (pendingSeries == null) return;
            sid = pendingSeries; vid = pendingVid; idx = pendingIndex; pos = pendingPos; dur = pendingDur;
            title = pendingTitle; count = pendingCount; session = pendingGeneration;
        }
        executeForSession(session, () -> {
            if (session != Session.generation()) return;
            try { syncProgress(sid, vid, title, idx, pos, dur, count, session); }
            catch (Exception e) { log("progress", e); }
        });
    }

    static void syncProgress(String sid, String vid, String title, int idx, int pos, int dur, int count, long session) throws Exception {
        if (vid == null || vid.isEmpty() || !Store.pendingProgressIsCurrent(sid, vid, idx, pos, session)) return;
        long historySubmittedAt = Store.progressSubmittedAt(sid, vid, idx, pos, session);
        long progressRateSubmittedAt = Store.progressRateSubmittedAt(sid, vid, idx, pos, session);
        if (historySubmittedAt == 0 || progressRateSubmittedAt == 0)
            Store.splitProgressSubmissions(sid, vid, idx, pos, session);

        long submittedAt = historySubmittedAt > 0 ? historySubmittedAt : System.currentTimeMillis();
        Exception failure = null;
        if (progressRateSubmittedAt == 0) {
            try {
                Account.uploadProgress(sid, vid, idx, pos, dur, count);
                Store.progressRateSubmitted(sid, vid, idx, pos, System.currentTimeMillis(), session);
            } catch (Exception e) {
                if (isSessionExpired(e)) throw e;
                failure = e;
            }
        }
        if (historySubmittedAt == 0 && Store.pendingProgressIsCurrent(sid, vid, idx, pos, session)) {
            try {
                Account.reportHistory(sid, title, vid, idx, pos, dur, count);
                Store.progressSubmitted(sid, vid, idx, pos, submittedAt, session);
            } catch (Exception e) {
                if (isSessionExpired(e)) throw e;
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
        if (session != Session.generation()) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastProgressConfirm < CONFIRM_INTERVAL_MS) return;   // 上传已被服务端受理，确认放到稍后
        lastProgressConfirm = nowMs;
        for (Account.Entry entry : Account.history(50)) {
            if (sid.equals(entry.seriesId) && entry.episodeIndex == idx && (entry.vid == null || vid.equals(entry.vid)) && entry.time >= submittedAt - 1000L
                    && Math.abs((long) entry.positionMs - pos) <= 5000L) {
                Store.progressSynced(sid, idx, pos, session);
                return;
            }
        }
        // Accepted uploads can take minutes to appear in history. Keep the local
        // snapshot and check again on the next pull without resending it.
    }

    private static boolean isSessionExpired(Exception e) {
        return e instanceof Account.AccountException && ((Account.AccountException) e).sessionExpired;
    }

    // ------------------------------------------------------------ Store.Sync 钩子

    /** 收藏写回先攒一小会儿再合并成批量请求：清空 300 条收藏只发 10 个请求，而不是 300 个。 */
    private static final java.util.Map<String, Boolean> favoriteBatch = new java.util.LinkedHashMap<>();
    private static boolean favoriteFlushQueued;
    private static final int FAVORITE_BATCH = 30;

    @Override public void favorite(final String seriesId, final boolean add) {
        if (!Session.loggedIn()) return;
        final long session = Session.generation();
        synchronized (favoriteBatch) {
            favoriteBatch.put(seriesId, add);
            if (favoriteFlushQueued) return;
            favoriteFlushQueued = true;
        }
        executeForSession(session, () -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            java.util.Map<String, Boolean> batch;
            synchronized (favoriteBatch) {
                batch = new java.util.LinkedHashMap<>(favoriteBatch);
                favoriteBatch.clear(); favoriteFlushQueued = false;
            }
            if (session != Session.generation()) return;
            try { flushFavorites(batch, session); }
            catch (Exception e) {
                log("favorite", e);
                if (session == Session.generation()) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(App.ctx(), "收藏同步失败，请重试", android.widget.Toast.LENGTH_LONG).show());
                    pull();
                }
            }
        });
    }

    private static void flushFavorites(java.util.Map<String, Boolean> batch, long session) throws Exception {
        for (boolean add : new boolean[]{true, false}) {
            List<String> ids = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Boolean> e : batch.entrySet())
                if (e.getValue() == add && Store.favoriteIntentIsCurrent(e.getKey(), add, session)) ids.add(e.getKey());
            for (int from = 0; from < ids.size(); from += FAVORITE_BATCH) {
                if (session != Session.generation()) return;
                List<String> chunk = ids.subList(from, Math.min(ids.size(), from + FAVORITE_BATCH));
                Account.setFavorites(chunk, add);   // 回读书架确认
                for (String id : chunk) Store.favoriteSyncedIfCurrent(id, add, session);
            }
        }
    }

    /** Writes and verifies the latest persisted intent; old queued toggles cannot clear a newer one. */
    private static void syncFavorite(String seriesId, boolean add, long session) throws Exception {
        if (!Store.favoriteIntentIsCurrent(seriesId, add, session)) return;
        Account.setFavorite(seriesId, add); // Account verifies the result by reading back the actual shelf.
        Store.favoriteSyncedIfCurrent(seriesId, add, session);
    }

    /** The APK fills shelf progress by book id; cap this supplemental read to once per 5 minutes. */
    private static synchronized boolean shouldPullFavoriteProgress(long generation) {
        long now = System.currentTimeMillis();
        if (favoriteProgressGeneration != generation || now - lastFavoriteProgressPull >= 5 * 60_000L) {
            favoriteProgressGeneration = generation;
            lastFavoriteProgressPull = now;
            return true;
        }
        return false;
    }

    @Override public void historyDeleted(final List<Store.Item> entries) {
        if (!Session.loggedIn() || entries.isEmpty()) return;
        final java.util.ArrayList<Store.Item> copy = new java.util.ArrayList<>(entries);
        final java.util.ArrayList<String> ids = historyIds(copy);
        if (ids.isEmpty()) return;
        final long requestedAt = System.currentTimeMillis();
        final long session = Session.generation();
        executeForSession(session, () -> {
            if (session != Session.generation()) return;
            try {
                Account.deleteHistory(copy);
                Store.applyHistoryDeletionIfCurrent(ids, requestedAt, session);
            }
            catch (Exception e) {
                log("history delete", e);
                if (session == Session.generation()) {
                    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                    handler.post(() -> android.widget.Toast.makeText(App.ctx(), "删除失败，请检查网络后重试", android.widget.Toast.LENGTH_LONG).show());
                    pull();
                }
            }
        });
    }

    private static long clearSequence, latestClear, clearSession;
    private static String clearState = "idle";
    private static synchronized long beginClear(long session) {
        latestClear = ++clearSequence; clearSession = session; clearState = "pending"; return latestClear;
    }
    private static synchronized void finishClear(long id, long session, String state) {
        if (id == latestClear) clearState = session == Session.generation() ? state : "cancelled";
    }
    public static synchronized String historyClearStatus(long id) {
        if (id == 0) return "success";
        if (id != latestClear) return "superseded";
        return clearSession == Session.generation() ? clearState : "cancelled";
    }

    @Override public long historyCleared(final List<Store.Item> localEntries, final long requestedAt) {
        if (!Session.loggedIn()) return -1;
        final long session = Session.generation();
        final long operation = beginClear(session);
        final java.util.ArrayList<Store.Item> local = new java.util.ArrayList<>(localEntries);
        executeForSession(session, () -> {
            String outcome = "failed";
            try {
                if (session != Session.generation()) return;
                List<Store.Item> copy = historyRowsToDelete(local, Store.history(),
                        Account.history(Store.HISTORY_LIMIT), requestedAt);
                if (session != Session.generation()) return;
                java.util.ArrayList<String> ids = historyIds(copy);
                Account.deleteHistory(copy);
                Store.applyHistoryDeletionIfCurrent(ids, requestedAt, session);
                outcome = "success";
            } catch (Exception e) {
                log("history clear", e);
                if (session == Session.generation()) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(App.ctx(), "清空失败，请检查网络后重试", android.widget.Toast.LENGTH_LONG).show());
                    pull();
                }
            } finally { finishClear(operation, session, outcome); }
        });
        return operation;
    }

    static java.util.ArrayList<Store.Item> historyRowsToDelete(List<Store.Item> requested,
            List<Store.Item> currentLocal, List<Account.Entry> cloud, long requestedAt) {
        java.util.Map<String, Store.Item> rows = new java.util.LinkedHashMap<>();
        java.util.Set<String> rewatched = new java.util.HashSet<>();
        for (Store.Item item : requested) if (item != null && item.seriesId != null && item.time <= requestedAt)
            rows.put(item.seriesId, item);
        for (Store.Item item : currentLocal) {
            if (item.time > requestedAt) { rows.remove(item.seriesId); rewatched.add(item.seriesId); }
            else if (!rows.containsKey(item.seriesId) || item.time > rows.get(item.seriesId).time)
                rows.put(item.seriesId, item);
        }
        for (Account.Entry entry : cloud) {
            if (rewatched.contains(entry.seriesId)) continue;
            if (entry.time > requestedAt) {
                rows.remove(entry.seriesId); // Keep a series watched again after the clear request.
                rewatched.add(entry.seriesId);
            } else {
                Store.Item before = rows.get(entry.seriesId);
                if (before == null || entry.time > before.time) rows.put(entry.seriesId, historyItem(entry));
            }
        }
        return new java.util.ArrayList<>(rows.values());
    }

    private static java.util.ArrayList<String> historyIds(List<Store.Item> entries) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (Store.Item item : entries)
            if (item != null && item.seriesId != null && !item.seriesId.isEmpty() && !ids.contains(item.seriesId))
                ids.add(item.seriesId);
        return ids;
    }

    private static Store.Item historyItem(Account.Entry entry) {
        Store.Item item = new Store.Item();
        item.seriesId = entry.seriesId; item.title = entry.title; item.cover = entry.cover; item.vid = entry.vid;
        item.episodeIndex = entry.episodeIndex; item.lastEpisode = entry.episodeIndex + 1;
        item.positionMs = entry.positionMs; item.watchedMs = entry.watchedMs;
        item.durationMs = entry.durationMs; item.episodeCount = entry.episodeCount; item.time = entry.time;
        return item;
    }

    @Override public void progress(String seriesId, String vid, int episodeIndex, int positionMs, int durationMs) {
        if (!Session.loggedIn() || vid == null) return;
        Store.Item local = Store.watched(seriesId);
        ink.pengfei.lvguo.net.Repo.Detail detail = ink.pengfei.lvguo.net.Repo.cachedDetail(seriesId);
        synchronized (LibrarySync.class) {
            boolean changedEpisode = pendingSeries == null || !seriesId.equals(pendingSeries) || pendingIndex != episodeIndex;
            pendingSeries = seriesId; pendingVid = vid; pendingIndex = episodeIndex; pendingPos = positionMs; pendingDur = durationMs;
            pendingGeneration = Session.generation();
            pendingTitle = local == null ? null : local.title;
            pendingCount = detail == null ? 0 : detail.episodes.size();
            if (changedEpisode) lastProgressUpload = 0;   // 换集立刻上报
        }
        long now = System.currentTimeMillis();
        if (now - lastProgressUpload < PROGRESS_THROTTLE_MS) return;   // 节流：播放中约每 5 秒一次
        lastProgressUpload = now;
        flushProgress();
    }

    private static void log(String what, Exception e) {
        // 任何同步请求发现登录已失效：统一按过期处理（回到扫码页并提示）
        if (e instanceof Account.AccountException && ((Account.AccountException) e).sessionExpired) { Session.expireIfCurrent(Account.requestGeneration()); return; }
        android.util.Log.w("LibrarySync", what + " failed: " + e.getMessage());
    }
}
