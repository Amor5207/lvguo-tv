package ink.pengfei.lvguo.net;

import ink.pengfei.lvguo.model.Series;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Uses the App search API, with the website as a fallback. */
public final class OnlineSearch {
    private OnlineSearch() {}
    /** APK route template /reading/bookapi/search/tab/v:version/ resolves to v1 on this endpoint. */
    static final String APP_SEARCH_TAB_PATH = "/reading/bookapi/search/tab/v1/";
    private static final Map<String,Long> relevant = new LinkedHashMap<String,Long>(32,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>32;}
    };
    private static String normalized(String value){
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]","");
    }
    private static boolean relevant(String query,List<Series> items)throws Exception{
        String key=normalized(query);
        synchronized(relevant){Long at=relevant.get(key);if(at!=null && System.currentTimeMillis()-at<600000)return true;}
        boolean found=false;
        for(Series s:items)if(s.title!=null && normalized(s.title).contains(key)){found=true;break;}
        if(!found){
            JSONObject suggestions=new JSONObject(read("/incent_resource/suggestion?app_id=8662&query="+URLEncoder.encode(query,"UTF-8")+"&count=50"));
            JSONArray rows=suggestions.optJSONArray("suggest_list");
            for(int i=0;rows!=null && i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String type=row.optString("word_type"), name=normalized(row.optString("name"));
                if((type.equals("short_play_name") && name.contains(key)) ||
                   ((type.equals("short_play_actor") || type.equals("short_play_category")) && name.equals(key))){found=true;break;}
            }
        }
        if(found)synchronized(relevant){relevant.put(key,System.currentTimeMillis());}
        return found;
    }
    /** 输入联想：优先使用 App 的搜索联想接口，失败时使用官网。 */
    public static List<String> suggest(String query) throws Exception {
        query = java.text.Normalizer.normalize(query == null ? "" : query.trim(), java.text.Normalizer.Form.NFKC);
        List<String> out = new ArrayList<>();
        if (query.isEmpty() || query.length() > 50) return out;
        try { return appSuggest(query); }
        catch (Exception e) { android.util.Log.w("HGSearch", "App suggestions unavailable", e); }
        JSONObject data = new JSONObject(read("/incent_resource/suggestion?app_id=8662&query=" + URLEncoder.encode(query, "UTF-8") + "&count=20"));
        JSONArray rows = data.optJSONArray("suggest_list");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; rows != null && i < rows.length() && seen.size() < 10; i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            String name = row.optString("name").trim();
            if (name.isEmpty()) { JSONObject v = row.optJSONObject("video_data"); if (v != null) name = v.optString("series_name", v.optString("title")).trim(); }
            if (!name.isEmpty()) seen.add(name);
        }
        out.addAll(seen);
        return out;
    }

    private static List<String> appSuggest(String query) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("version_code", "72232"); q.put("version_name", "7.2.2.32");
        q.put("manifest_version_code", "72232"); q.put("update_version_code", "72232"); q.put("pv_player", "72232");
        // The APK's SuggestRequest builder sets bookshelf_search_plan=4 and
        // leaves count at 0 for ShortPlay (it only raises count for Book/Comic).
        q.put("q", query); q.put("count", "0"); q.put("tab_type", "11");
        q.put("bookshelf_search_plan", "4"); q.put("search_source", "0"); q.put("bookstore_tab", "0");
        q.put("user_is_login", Session.loggedIn() ? "1" : "0");
        JSONObject response = Account.signed(Account.APP_HOST, "/reading/bookapi/search/suggest/v1/", q, null);
        if (response.optInt("code", -1) != 0) throw new IOException("搜索联想服务暂不可用");
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw new IOException("搜索联想响应不完整");
        return appSuggestItems(data);
    }

    /** The APK's SuggestResponse consumer reads query_result_v2 and skips ALADDIN rows. */
    static List<String> appSuggestItems(JSONObject data) {
        Set<String> seen = new LinkedHashSet<>();
        JSONArray rows = data.optJSONArray("query_result_v2");
        for (int i = 0; rows != null && i < rows.length() && seen.size() < 10; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optInt("show_type", 0) == 2) continue;
            String name = row.optString("name", "").trim();
            if (!name.isEmpty()) seen.add(name);
        }
        return new ArrayList<>(seen);
    }

    public static final class Page {
        public final List<Series> items = new ArrayList<>();
        public int number, total;
        public boolean more;
    }
    private static final Map<String, JSONObject> cursors = new LinkedHashMap<>();

    private static Page appPage(String query, int number) throws Exception {
        String key = query + "#" + number;
        JSONObject cursor;
        synchronized (cursors) { cursor = cursors.get(key); }
        if (number > 1 && cursor == null) throw new IOException("搜索结果已过期，请重新搜索");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("version_code", "72232"); q.put("version_name", "7.2.2.32");
        q.put("manifest_version_code", "72232"); q.put("update_version_code", "72232"); q.put("pv_player", "72232");
        // The native GetSearchPageRequest uses count=0 for the normal tab load.
        q.put("query", query); q.put("count", "0"); q.put("tab_type", "11");
        q.put("offset", cursor == null ? "0" : cursor.optString("next_offset", "0"));
        q.put("bookshelf_search_plan", "4"); q.put("search_source", "0"); q.put("use_correct", "true");
        q.put("user_is_login", Session.loggedIn() ? "1" : "0");
        if (cursor != null) { q.put("search_id", cursor.optString("search_id")); q.put("passback", cursor.optString("passback")); }
        JSONObject data = Account.signed(Account.APP_HOST, APP_SEARCH_TAB_PATH, q, null);
        if (data.optInt("code", 0) != 0) throw new IOException("搜索服务暂不可用");
        JSONArray tabs = data.optJSONArray("search_tabs"); JSONObject tab = null;
        for (int i = 0; tabs != null && i < tabs.length(); i++) {
            JSONObject t = tabs.optJSONObject(i); if (t != null && t.optInt("tab_type") == 11) { tab = t; break; }
        }
        if (tab == null) throw new IOException("搜索响应缺少结果");
        Page out = new Page(); out.number = number; out.more = tab.optBoolean("has_more");
        JSONArray rows = tab.optJSONArray("data"); Set<String> seen = new HashSet<>();
        for (int i = 0; rows != null && i < rows.length(); i++) collectAppCell(rows.optJSONObject(i), out.items, seen);
        if (out.more) {
            long previous = cursor == null ? 0 : cursor.optLong("next_offset", -1);
            long next = tab.optLong("next_offset", -1);
            if (previous < 0 || next <= previous) throw new IOException("搜索分页状态异常，请重新搜索");
            synchronized (cursors) { if (cursors.size() > 100) cursors.clear(); cursors.put(query + "#" + (number + 1), tab); }
        }
        out.total = appExpectedCount(tab);
        if (!out.items.isEmpty()) Catalog.merge(out.items);
        return out;
    }

    /** SearchTabData.expectedNum is serialized by the App as "expected_num". */
    static int appExpectedCount(JSONObject tab) {
        return tab == null ? 0 : tab.optInt("expected_num", 0);
    }

    private static void collectAppCell(JSONObject cell, List<Series> out, Set<String> seen) {
        if (cell == null) return;
        JSONArray videos = cell.optJSONArray("video_data");
        if (videos != null) for (int j = 0; j < videos.length(); j++) {
            JSONObject v = videos.optJSONObject(j); if (v == null) continue;
            String id = v.optString("series_id", cell.optString("book_id"));
            String title = v.optString("title", cell.optString("cell_name"));
            JSONObject high = cell.optJSONObject("search_high_light");
            if (high != null && high.optJSONObject("title") != null) title = high.optJSONObject("title").optString("text", title);
            title = title.replaceAll("</?em>", "").trim();
            if (id.isEmpty() || title.isEmpty() || !seen.add(id)) continue;
            Series item = new Series(); item.seriesId = id; item.title = title;
            item.cover = v.optString("cover", v.optString("cover_url")); item.intro = v.optString("video_desc");
            item.episodeCount = v.optInt("episode_cnt"); item.firstVid = v.optString("vid", null); out.add(item);
        }
        try {
            String raw = cell.optString("lynx_data", "");
            if (!raw.isEmpty()) { JSONArray nested = new JSONObject(raw).optJSONArray("cell_data");
                for (int i = 0; nested != null && i < nested.length(); i++) collectAppCell(nested.optJSONObject(i), out, seen); }
        } catch (JSONException ignored) { }
    }

    public static Page page(String query, int number) throws Exception {
        query = java.text.Normalizer.normalize(query == null ? "" : query.trim(), java.text.Normalizer.Form.NFKC);
        if (query.isEmpty() || query.length() > 100) throw new IOException("请输入剧名，最多100字");
        // 第 1 页由网页搜索兜底时，后续页也必须走网页搜索（App 搜索的分页游标不存在）
        if (number > 1 && servedByWeb(query)) return webPage(query, number);
        try { Page result = appPage(query, Math.max(1, number)); if (!result.items.isEmpty() || number > 1) return result; }
        catch (Exception e) { if (number > 1) throw e; android.util.Log.w("HGSearch", "App search unavailable", e); }
        Page fallback = webPage(query, number);
        if (number <= 1) markWeb(query);
        return fallback;
    }

    private static final Set<String> webQueries = new HashSet<>();
    private static boolean servedByWeb(String query) { synchronized (webQueries) { return webQueries.contains(query); } }
    private static void markWeb(String query) { synchronized (webQueries) { if (webQueries.size() > 100) webQueries.clear(); webQueries.add(query); } }

    private static Page webPage(String query, int number) throws Exception {
        query = java.text.Normalizer.normalize(query == null ? "" : query.trim(), java.text.Normalizer.Form.NFKC);
        if(query.isEmpty() || query.length()>100)throw new IOException("请输入剧名，最多100字");
        number=Math.max(1,number);
        String path="/api/search/result?query="+URLEncoder.encode(query,"UTF-8")+(number>1?"&page="+number:"");
        JSONObject data;
        try{data=new JSONObject(read(path));}
        catch(IOException error){
            if(number!=1)throw error;
            JSONObject suggestions=new JSONObject(read("/incent_resource/suggestion?app_id=8662&query="+URLEncoder.encode(query,"UTF-8")));
            JSONArray fallback=suggestions.optJSONArray("suggest_list");
            if(fallback==null || fallback.length()==0)throw error;
            data=new JSONObject().put("searchList",fallback).put("pageNum",1).put("totalCount",fallback.length()).put("hasMore",false);
        }
        JSONArray rows=data.optJSONArray("searchList");
        if(rows==null || data.optInt("pageNum",number)!=number)
            throw new IOException("搜索服务响应异常，请重试");
        Page out=new Page();out.number=number;out.total=data.optInt("totalCount");out.more=data.optBoolean("hasMore");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i),v=row==null?null:row.optJSONObject("video_data");
            if(v!=null)Browse.collect(v,out.items,seen);
        }
        if(rows.length()>0 && out.items.isEmpty())throw new IOException("搜索结果格式已变化，请重试");
        // The upstream also returns generic recommendations for unknown terms.
        // Require a title, actor or category match before accepting that result set.
        if(!out.items.isEmpty() && !relevant(query,out.items)){out.items.clear();out.total=0;out.more=false;}
        if(!out.items.isEmpty())Catalog.merge(out.items);
        android.util.Log.i("HGSearch","official api page="+number+" items="+out.items.size()+" total="+out.total+" more="+out.more);
        return out;
    }
    private static String read(String path)throws Exception {
        IOException failure=null;
        for(int attempt=0;attempt<2;attempt++)try{return readOnce(path);}catch(IOException error){failure=error;}
        throw failure;
    }
    private static String readOnce(String path)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("https://hongguoduanju.com"+path).openConnection();
        c.setConnectTimeout(7000);c.setReadTimeout(10000);c.setUseCaches(false);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");
        c.setRequestProperty("Referer","https://hongguoduanju.com/");
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
        try{
            if(c.getResponseCode()!=200)throw new IOException("搜索服务暂不可用，请重试");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;
                while((n=in.read(block))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("搜索响应过大");out.write(block,0,n);}
                return new String(out.toByteArray(),"UTF-8");
            }
        }finally{c.disconnect();}
    }
}
