package com.hgtv.app.net;

import com.hgtv.app.App;
import com.hgtv.app.model.Series;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地剧库缓存，保存已发现的剧目，供推荐和离线索引使用。
 * 在线搜索由 OnlineSearch 获取服务端分页结果，不以该缓存代替在线搜索。
 */
public final class Catalog {

    private static final long TTL = 6 * 60 * 60 * 1000L;   // 6 小时

    private static volatile List<Series> sList;
    private static long sTs;
    private static volatile boolean sRefreshing;

    private Catalog() {}

    public static synchronized void merge(List<Series> items) {
        Map<String,Series> map=new LinkedHashMap<>();
        List<Series> existing=cached();
        if(existing!=null)for(Series s:existing)map.put(s.seriesId,s);
        if(items!=null)for(Series s:items)map.put(s.seriesId,s);
        sList=new ArrayList<>(map.values());sTs=System.currentTimeMillis();save(sList);
    }

    private static File file() {
        return new File(App.ctx().getFilesDir(), "catalog.json");
    }

    /** 已有缓存则立即返回（可能为空），不阻塞 */
    public static List<Series> cached() {
        if (sList != null) return sList;
        try {
            File f = file();
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            sTs = o.optLong("ts");
            JSONArray arr = o.optJSONArray("items");
            List<Series> list = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject x = arr.optJSONObject(i);
                    if (x == null) continue;
                    list.add(fromJson(x));
                }
            }
            sList = list;
            return list;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean stale() {
        return System.currentTimeMillis() - sTs > TTL;
    }

    /** 拉网络重建索引（阻塞，放 IO 线程）。失败时保留旧数据。 */
    public static List<Series> refresh() {
        if (sRefreshing) return sList;
        sRefreshing = true;
        try {
            Map<String, Series> map = new LinkedHashMap<>();
            try {
                for (Series s : Repo.rank(Repo.BOARD_HOT, null, 120)) map.put(s.seriesId, s);
            } catch (Throwable ignored) {
            }
            try {
                for (Series s : Repo.rank(Repo.BOARD_NEW, null, 120)) {
                    if (!map.containsKey(s.seriesId)) map.put(s.seriesId, s);
                }
            } catch (Throwable ignored) {
            }
            if (map.isEmpty()) return sList;

            List<Series> list = new ArrayList<>(map.values());
            merge(list);
            return sList;
        } finally {
            sRefreshing = false;
        }
    }

    /** 进入 App 时预热一次（不阻塞 UI） */
    public static void warm() {
        App.io().execute(() -> {
            List<Series> c = cached();
            if (c == null || c.isEmpty() || stale()) refresh();
        });
    }

    private static void save(List<Series> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Series s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.seriesId);
                o.put("t", s.title);
                o.put("c", s.cover);
                o.put("n", s.episodeCount);
                o.put("p", s.playCount);o.put("vid",s.firstVid);o.put("intro",s.intro);o.put("category",s.category);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("ts", sTs);
            root.put("items", arr);
            File tmp = new File(file().getAbsolutePath() + ".t");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(root.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            if (!tmp.renameTo(file())) tmp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static Series fromJson(JSONObject o) {
        Series s = new Series();
        s.seriesId = o.optString("id");
        s.title = o.optString("t");
        s.cover = o.optString("c");
        s.episodeCount = o.optInt("n");
        s.playCount = o.optLong("p");s.firstVid=o.optString("vid",null);s.intro=o.optString("intro");s.category=o.optString("category");
        return s;
    }

    // ------------------------------------------------------------ 本地搜索 / 筛选

    /** 标题分词匹配：空格分隔多个关键词，需全部命中 */
    public static List<Series> search(List<Series> src, String query) {
        List<Series> out = new ArrayList<>();
        if (src == null || query == null) return out;
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return out;
        String[] kws = q.split("[\\s，,]+");
        for (Series s : src) {
            if (s.title == null) continue;
            String t = s.title.toLowerCase();
            boolean ok = true;
            for (String k : kws) {
                if (k.isEmpty()) continue;
                if (!t.contains(k)) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(s);
        }
        return out;
    }
}
