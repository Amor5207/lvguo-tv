package com.hgtv.app.net;

import com.hgtv.app.model.Episode;
import com.hgtv.app.model.Gear;
import com.hgtv.app.model.Series;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 业务接口封装：榜单 / 详情 / 播放地址 */
public final class Repo {

    private Repo() {}

    /**
     * 接口结果缓存：详情 10 分钟、播放地址 5 分钟。
     * 目的有两个：① 减少请求，避开服务端软限流；② 返回详情/起播不用等网络。
     */
    private static final java.util.Map<String, Object[]> CACHE = new java.util.LinkedHashMap<String,Object[]>(64,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,Object[]> entry){return size()>48;}
    };

    @SuppressWarnings("unchecked")
    private static <T> T cacheGet(String key) {
        synchronized (CACHE) {
            Object[] e = CACHE.get(key);
            if (e == null) return null;
            if (System.currentTimeMillis() > (Long) e[0]) {
                CACHE.remove(key);
                return null;
            }
            return (T) e[1];
        }
    }

    private static void cachePut(String key, Object value, long ttlMs) {
        synchronized (CACHE) {
            CACHE.put(key, new Object[]{System.currentTimeMillis() + ttlMs, value});
        }
    }

    /** 空 body（Content-Length: 0）表示被静默拒绝，退避后重试 */
    private static JSONObject callRetry(String path, JSONObject body, java.util.Map<String, String> extra)
            throws Exception {
        JSONObject last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(700L * attempt);
            last = HgApi.call(path, body, extra);
            if (last.length() > 0 && !last.optBoolean("_empty")) return last;
            // HTTP 200 + Content-Length: 0 是服务端确定性空包（已实测确诊），
            // 退避重试没有意义，直接失败走降级，避免用户干等。
            if (last != null && last.optBoolean("_empty")
                    && last.optInt("_http_status", -1) == 200) {
                break;
            }
        }
        String st = last == null ? "?" : String.valueOf(last.optInt("_http_status", -1));
        throw new IllegalStateException("接口无响应(HTTP " + st + ")");
    }

    // ------------------------------------------------------------ 榜单

    /** 板块：短剧热榜 / 推荐 / 新剧 */
    public static final String BOARD_HOT = "ranklist_hot_play_sc";
    public static final String BOARD_RECOMMEND = "ranklist_hot_sc";
    public static final String BOARD_NEW = "ranklist_new_rank_sc";

    public static final String TAB_VIDEO = "26";

    /** cell_id 必填，空值会返回 100103 PARAM_INVALID */
    public static final String DEFAULT_CELL = "7470092475068071998";

    public static List<Series> rank(String board, String cellId, int limit) throws Exception {
        if (cellId == null || cellId.isEmpty()) cellId = DEFAULT_CELL;
        List<Series> out = new ArrayList<>();
        String session = UUID.randomUUID().toString();
        int offset = 0;
        while (out.size() < limit) {
            Map<String, String> q = new HashMap<>();
            q.put("cell_id", cellId);
            q.put("tab_type", TAB_VIDEO);
            q.put("client_req_type", "2");
            q.put("client_template", "2");
            q.put("screen_width_px", "1350");
            q.put("selected_items", "all");
            q.put("sub_selected_items", board);
            q.put("session_uuid", session);
            if (offset > 0) q.put("offset", String.valueOf(offset));

            JSONObject j = HgApi.call("/reading/bookapi/bookmall/cell/change/v", null, q);
            if (j.optInt("code", -1) != 0) break;
            JSONObject cv = j.optJSONObject("data");
            cv = cv == null ? null : cv.optJSONObject("cell_view");
            if (cv == null) break;
            JSONArray cells = cv.optJSONArray("cell_data");
            if (cells == null || cells.length() == 0) break;

            for (int i = 0; i < cells.length(); i++) {
                JSONObject it = cells.optJSONObject(i);
                if (it == null) continue;
                Object vd = it.opt("video_data");
                JSONObject v = null;
                if (vd instanceof JSONArray) {
                    JSONArray arr = (JSONArray) vd;
                    v = arr.length() > 0 ? arr.optJSONObject(0) : null;
                } else if (vd instanceof JSONObject) {
                    v = (JSONObject) vd;
                }
                if (v == null) continue;
                String sid = v.optString("series_id", "");
                if (sid.isEmpty()) continue;
                Series s = new Series();
                s.seriesId = sid;
                s.title = optStr(v, "series_title", optStr(v, "title", ""));
                s.episodeCount = v.optInt("episode_cnt", 0);
                s.playCount = v.optLong("play_cnt", 0);
                s.cover = optStr(v, "cover", optStr(v, "series_cover", ""));
                s.firstVid = v.optString("vid", null);
                s.durationSec = v.optInt("duration", 0);
                s.rank = out.size() + 1;
                out.add(s);
            }
            if (!cv.optBoolean("has_more", false)) break;
            offset = cv.optInt("next_offset", offset + cells.length());
        }
        if (out.size() > limit) return out.subList(0, limit);
        return out;
    }

    // ------------------------------------------------------------ 详情

    public static final class Detail {
        public String title;
        public String cover;
        public String intro;
        public int episodeCount;
        public boolean multiSeason;
        /** 第几季（multiSeason 时有效，0 = 未知） */
        public int seasonIndex;
        /** 多季合集 ID；不是可直接播放的 series_id。 */
        public String relatedAlbumId;
        /** pay_info.pay_type：0 = 免费 */
        public int payType = -1;
        /** 评分文案，如 "9.0分" */
        public String rating;
        /** 热度文案，如 "4566万"（从 series_sub_title_list 的"红果热度值xxx"提取） */
        public String heatText;
        /** 爆剧/热播等运营标签，如 "爆剧" */
        public String hotTag;
        public long playCount;
        /** 演员名列表（celebrities） */
        public final List<String> actors = new ArrayList<>();
        public final List<Episode> episodes = new ArrayList<>();

        public boolean free() {
            return payType == 0;
        }
    }

    /**
     * 剧集详情：走 album_detail 旁路（2026-10-21 实测）。
     *
     * POST /novel/player/album_detail/v1/ 对该请求免签返回完整数据：
     *   - album_id 必须是 string；series_ids 元素必须是数字（int），每批 ≤21
     *   - need_video_detail_info=true 才有 video_detail_data
     *   - 响应：data.video_detail_data[series_id].video_data 即 VideoDetailInfo
     *     （video_list = 选集；secondary_infos[data_type=4] = 评分）
     * 对照：multi_video_detail 对未签名请求已确定性返空包（HTTP 200 + 0 字节）。
     */
    /** Local metadata only, for deciding whether a history entry has episodes left. */
    public static Detail cachedDetail(String seriesId) {
        Detail hit=cacheGet("detail:"+seriesId);
        return hit!=null?hit:loadDetail(seriesId);
    }

    public static Detail detail(String seriesId) throws Exception {
        String ck = "detail:" + seriesId;
        Detail cached = cacheGet(ck);
        if (cached != null) return cached;

        Detail disk = loadDetail(seriesId);
        if (disk != null) {
            cachePut(ck, disk, 10 * 60 * 1000L);
            return disk;
        }

        // 负缓存：album_detail 失败（下架/网络）2 分钟内不重复试探，
        // 避免用户连续打开几部剧时每部都白等一次网络往返。


        Detail d;
        try {
            d = parseAlbumDetail(callRetry(ALBUM_DETAIL, albumBody(seriesId, null), null), seriesId);
        } catch (IllegalStateException e) {

            throw e;
        }
        cachePut(ck, d, 10 * 60 * 1000L);
        saveDetail(seriesId, d);
        return d;
    }

    /** 手动重试：清掉负缓存再拉一次（详情页"重试"按钮用） */
    public static Detail retryDetail(String seriesId) throws Exception {
        synchronized (CACHE) {
            CACHE.remove("detail_fail:" + seriesId);
        }
        return detail(seriesId);
    }

    // ------------------------------------------------------------ album_detail

    private static final String ALBUM_DETAIL = "/novel/player/album_detail/v1/";
    /** series_ids 每批上限：21 个以内正常返回，22 个起整体返空（不报错） */
    private static final int DETAIL_BATCH = 21;

    /** album_detail 专用 biz_param：与 tools/hg_api.py 的 DETAIL_BIZ 一致（真机实测） */
    private static JSONObject albumBiz() throws Exception {
        JSONObject biz = new JSONObject();
        biz.put("video_platform", 1024);            // VideoPlatformType.Mixed —— 唯一被接受的取值
        biz.put("need_all_video_definition", true);
        biz.put("need_mp4_align", true);
        biz.put("use_os_player", false);
        biz.put("device_level", HgApi.DEVICE_LEVEL_H265);   // >=4 才有标准 H.265
        biz.put("screen_width_px", "1350");
        biz.put("source", 7);
        biz.put("video_id_type", 1);
        return biz;
    }

    /**
     * 请求体：album_id 必须为 string；series_ids 元素必须为 JSON 数字
     * （服务端会把 string 报成 expected int, actual value: ... (string)）。
     * batch 为 null 时只查 seriesId 一部。
     */
    private static JSONObject albumBody(String seriesId, List<String> batch) throws Exception {
        JSONObject body = new JSONObject();
        body.put("album_id", seriesId);
        JSONArray ids = new JSONArray();
        if (batch == null) {
            ids.put(Long.parseLong(seriesId));
        } else {
            for (String s : batch) ids.put(Long.parseLong(s));
        }
        body.put("series_ids", ids);
        body.put("biz_param", albumBiz());
        body.put("need_video_detail_info", true);
        return body;
    }

    /** 只接受请求的剧集节点，避免缺失条目被误替换成另一部剧。 */
    private static JSONObject videoData(JSONObject j, String seriesId) {
        JSONObject data = j == null ? null : j.optJSONObject("data");
        if (data == null) return null;
        JSONObject vdd = data.optJSONObject("video_detail_data");
        if (vdd == null) return null;
        JSONObject node = vdd.optJSONObject(seriesId);
        return node == null ? null : node.optJSONObject("video_data");
    }

    public static final class Season {
        public String id, title, cover;
        public int number, episodes;
    }

    /** 合集条目中有占位 ID，必须再以播放详情验证，不能直接全部当作季。 */
    private static final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.FutureTask<List<Season>>> SEASON_REQUESTS=new java.util.concurrent.ConcurrentHashMap<>();
    public static List<Season> seasons(String seriesId,String albumId)throws Exception{
        if(albumId==null||albumId.isEmpty())return new ArrayList<>();
        List<Season> memory=cacheGet("seasons_v2:"+albumId);if(seasonListComplete(memory,seriesId))return memory;
        try{JSONObject saved=new JSONObject(com.hgtv.app.App.prefs().getString("season_meta_v2_"+albumId,"{}"));
            if(System.currentTimeMillis()-saved.optLong("ts")<6*60*60*1000L){JSONArray rows=saved.getJSONArray("rows");List<Season> result=new ArrayList<>();
                for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);Season season=new Season();season.id=row.getString("id");season.title=row.optString("title");season.cover=row.optString("cover");season.number=row.getInt("number");season.episodes=row.optInt("count");if(season.id.matches("[0-9]+")&&season.number>0)result.add(season);}
                if(seasonListComplete(result,seriesId)){cachePut("seasons_v2:"+albumId,result,5*60*1000L);return result;}
            }
        }catch(Exception ignored){}
        java.util.concurrent.FutureTask<List<Season>> task=new java.util.concurrent.FutureTask<>(()->fetchSeasons(seriesId,albumId));
        java.util.concurrent.FutureTask<List<Season>> existing=SEASON_REQUESTS.putIfAbsent(albumId,task);if(existing!=null)task=existing;else task.run();
        try{return task.get();}catch(java.util.concurrent.ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new java.io.IOException("季列表加载失败",cause);}finally{SEASON_REQUESTS.remove(albumId,task);}
    }
    private static boolean seasonListComplete(List<Season> rows,String currentId){
        if(rows==null||rows.isEmpty())return false;
        boolean current=false,first=false;
        for(Season row:rows){current|=currentId.equals(row.id);first|=row.number==1;}
        return current&&first;
    }
    private static List<Season> fetchSeasons(String seriesId, String albumId) throws Exception {
        if (albumId == null || albumId.isEmpty()) return new ArrayList<>();
        String key="seasons_v2:"+albumId;
        List<Season> hit=cacheGet(key);if(seasonListComplete(hit,seriesId))return hit;
        Map<String,String> q=new java.util.LinkedHashMap<>();
        q.put("album_id",albumId);q.put("count","100");q.put("offset","0");
        JSONObject root=HgApi.call("/api/novel/origin/album/get_video_list/v1/",null,q);
        JSONObject data=root.optJSONObject("data");
        JSONArray videos=data==null?null:data.optJSONArray("video_data_list");
        if(root.optInt("code",-1)!=0 || videos==null)throw new java.io.IOException("季列表加载失败，请重试");
        List<String> ids=new ArrayList<>();
        for(int n=0;n<videos.length();n++){
            JSONObject v=videos.optJSONObject(n);String id=v==null?"":v.optString("video_id","");
            if(id.matches("[0-9]+") && !ids.contains(id))ids.add(id);
        }
        if(!ids.contains(seriesId))ids.add(seriesId);
        List<Season> result=new ArrayList<>();
        java.util.Iterator<String> pending=ids.iterator();while(pending.hasNext()){String id=pending.next();Detail known=cacheGet("detail:"+id);if(known==null)known=loadDetail(id);if(known==null)continue;
            // 不完整或不属于本合集的缓存仍需向接口确认，不能直接吞掉该季。
            if(!known.multiSeason||known.seasonIndex<=0||(known.relatedAlbumId!=null&&!known.relatedAlbumId.isEmpty()&&!albumId.equals(known.relatedAlbumId)))continue;
            pending.remove();
            Season season=new Season();season.id=id;season.title=known.title;season.cover=known.cover;season.number=known.seasonIndex;season.episodes=known.episodes.size();result.add(season);
        }
        for(int n=0;n<ids.size();n+=DETAIL_BATCH){
            List<String> batch=ids.subList(n,Math.min(ids.size(),n+DETAIL_BATCH));
            JSONObject details=callRetry(ALBUM_DETAIL,albumBody(seriesId,batch),null);
            if(details.optInt("code",-1)!=0)throw new java.io.IOException("季详情加载失败，请重试");
            for(String id:batch){
                JSONObject response=details;JSONObject v=videoData(response,id);
                if(v==null){try{response=callRetry(ALBUM_DETAIL,albumBody(id,java.util.Collections.singletonList(id)),null);v=videoData(response,id);}catch(Exception ignored){}}
                if(v==null)continue;
                Detail d=parseAlbumDetail(response,id);
                if(!d.multiSeason || d.seasonIndex<=0 || (d.relatedAlbumId!=null && !d.relatedAlbumId.isEmpty() && !albumId.equals(d.relatedAlbumId)))continue;
                Season s=new Season();s.id=id;s.title=d.title;s.cover=v.optString("series_cover","");
                s.number=d.seasonIndex;s.episodes=d.episodes.size();result.add(s);
                cachePut("detail:"+id,d,10*60*1000L);saveDetail(id,d);
            }
        }
        boolean containsCurrent=false;for(Season row:result)if(seriesId.equals(row.id))containsCurrent=true;
        if(!containsCurrent){Detail current=detail(seriesId);if(current.multiSeason&&current.seasonIndex>0){Season row=new Season();row.id=seriesId;row.title=current.title;row.cover=current.cover;row.number=current.seasonIndex;row.episodes=current.episodes.size();result.add(row);}}
        java.util.Collections.sort(result,(a,b)->Integer.compare(a.number,b.number));
        if(result.isEmpty())throw new java.io.IOException("季列表暂未返回，请重试");
        List<Series> indexed=new ArrayList<>();for(Season season:result)if(season.episodes>0){
            Series s=new Series();s.seriesId=season.id;s.title=season.title;s.cover=season.cover;s.episodeCount=season.episodes;
            Detail d=cacheGet("detail:"+season.id);if(d!=null){s.intro=d.intro;s.playCount=d.playCount;if(!d.episodes.isEmpty())s.firstVid=d.episodes.get(0).vid;}
            indexed.add(s);
        }
        Catalog.merge(indexed);
        try{JSONArray rows=new JSONArray();for(Season season:result)rows.put(new JSONObject().put("id",season.id).put("title",season.title).put("cover",season.cover).put("number",season.number).put("count",season.episodes));com.hgtv.app.App.prefs().edit().putString("season_meta_v2_"+albumId,new JSONObject().put("ts",System.currentTimeMillis()).put("rows",rows).toString()).apply();}catch(Exception ignored){}
        cachePut(key,result,5*60*1000L);return result;
    }

    private static Detail parseAlbumDetail(JSONObject j, String seriesId) throws Exception {
        if (j == null) throw new IllegalStateException("详情为空");
        // 下架等服务端业务错误：message 形如"合集已下架"
        if (j.optInt("code", -1) != 0) {
            String msg = j.optString("message", "");
            throw new IllegalStateException(msg == null || msg.isEmpty() ? "详情不可用" : msg);
        }
        JSONObject vd = videoData(j, seriesId);
        if (vd == null) throw new IllegalStateException("详情节点为空");

        Detail d = new Detail();
        d.title = optStr(vd, "series_title", "");
        d.cover = optStr(vd, "series_cover", "");
        d.intro = optStr(vd, "series_intro", "");
        if (d.intro.isEmpty()) {
            // 兜底：album_data.intro 与 series_intro 基本一致
            JSONObject data = j.optJSONObject("data");
            JSONObject ad = data == null ? null : data.optJSONObject("album_data");
            if (ad != null) d.intro = optStr(ad, "intro", "");
        }
        d.episodeCount = vd.optInt("episode_cnt", 0);
        d.multiSeason = vd.optBoolean("is_multi_season", false);
        d.seasonIndex = vd.optInt("season_index", 0);
        d.relatedAlbumId = vd.optString("related_album_id", null);
        d.playCount = vd.optLong("series_play_cnt", 0);

        JSONObject pay = vd.optJSONObject("pay_info");
        if (pay != null) d.payType = pay.optInt("pay_type", -1);

        // 评分：secondary_infos 里 data_type==4 的 content，如 "9.0分"
        JSONArray si = vd.optJSONArray("secondary_infos");
        if (si != null) {
            for (int i = 0; i < si.length(); i++) {
                JSONObject o = si.optJSONObject(i);
                if (o != null && o.optInt("data_type", -1) == 4) {
                    d.rating = optStr(o, "content", null);
                    break;
                }
            }
        }

        // 副标题：["全66集","红果热度值5024万"]；热度剥掉前缀只留数值
        JSONArray sub = vd.optJSONArray("series_sub_title_list");
        if (sub != null) {
            for (int i = 0; i < sub.length(); i++) {
                String s = sub.optString(i, "");
                if (s.isEmpty()) continue;
                if (s.startsWith("红果热度值")) {
                    d.heatText = s.substring("红果热度值".length());
                } else if (d.heatText == null && s.contains("热度")) {
                    int p = s.indexOf("热度");
                    d.heatText = s.substring(p + 2);
                }
            }
        }

        // 运营标签（爆剧）
        JSONObject vti = vd.optJSONObject("video_tag_info");
        if (vti != null) d.hotTag = optStr(vti, "text", null);

        // 演员
        JSONArray cels = vd.optJSONArray("celebrities");
        if (cels != null) {
            for (int i = 0; i < cels.length() && d.actors.size() < 8; i++) {
                JSONObject c = cels.optJSONObject(i);
                if (c == null) continue;
                String name = optStr(c, "nickname", null);
                if (name != null) d.actors.add(name);
            }
        }

        // 选集
        JSONArray list = vd.optJSONArray("video_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e == null) continue;
                Episode ep = new Episode();
                ep.index = e.optInt("vid_index", i + 1);
                ep.title = optStr(e, "title", "第" + ep.index + "集");
                ep.vid = e.optString("vid", "");
                ep.durationSec = e.optInt("duration", 0);
                ep.cover = optStr(e, "episode_cover", optStr(e, "cover", ""));
                if (!ep.vid.isEmpty()) d.episodes.add(ep);
            }
        }
        return d;
    }

    /**
     * 批量预热：一次请求最多 21 部剧（服务端上限）。
     * 首页榜单到达后后台调用，让"首页 → 详情"基本零等待。任何失败都静默。
     */
    public static java.util.Map<String, Detail> prefetch(java.util.Collection<String> seriesIds) {
        java.util.Map<String, Detail> out = new java.util.HashMap<>();
        if (seriesIds == null || seriesIds.isEmpty()) return out;
        List<String> ids = new ArrayList<>();
        for (String s : seriesIds) {
            if (s == null || s.isEmpty() || ids.contains(s)) continue;
            if (cacheGet("detail:" + s) != null || loadDetail(s) != null) continue;   // 已有就不重复拉
            ids.add(s);
        }
        for (int i = 0; i < ids.size(); i += DETAIL_BATCH) {
            List<String> batch = ids.subList(i, Math.min(ids.size(), i + DETAIL_BATCH));
            try {
                JSONObject j = callRetry(ALBUM_DETAIL, albumBody(batch.get(0), batch), null);
                if (j.optInt("code", -1) != 0) continue;
                JSONObject data = j.optJSONObject("data");
                JSONObject vdd = data == null ? null : data.optJSONObject("video_detail_data");
                if (vdd == null) continue;
                java.util.Iterator<String> it = vdd.keys();
                while (it.hasNext()) {
                    String sid = it.next();
                    JSONObject node = vdd.optJSONObject(sid);
                    if (node == null || !node.has("video_data")) continue;
                    try {
                        // parseAlbumDetail 走 videoData(j, sid) 精确匹配，逐个解析
                        Detail d = parseAlbumDetail(j, sid);
                        cachePut("detail:" + sid, d, 10 * 60 * 1000L);
                        saveDetail(sid, d);
                        out.put(sid, d);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
                // 预热失败不影响任何流程
            }
        }
        return out;
    }

    // ---- 详情的磁盘缓存：重启后不用再请求（详情变更很少，60 分钟内直接用）
    // v2 = album_detail 版（2026-10-21）。旧版缺评分/热度/演员等字段，直接作废。
    private static final long DISK_TTL = 60 * 60 * 1000L;
    private static final int DISK_VER = 2;

    private static java.io.File detailFile(String seriesId) {
        java.io.File d = new java.io.File(com.hgtv.app.App.ctx().getFilesDir(), "detail");
        if (!d.exists()) d.mkdirs();
        return new java.io.File(d, seriesId + ".json");
    }

    private static void saveDetail(String seriesId, Detail d) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", DISK_VER);
            o.put("ts", System.currentTimeMillis());
            o.put("title", d.title);
            o.put("cover", d.cover);
            o.put("intro", d.intro);
            o.put("cnt", d.episodeCount);
            o.put("multi", d.multiSeason);
            o.put("si", d.seasonIndex);
            o.put("rel", d.relatedAlbumId == null ? "" : d.relatedAlbumId);
            o.put("pay", d.payType);
            o.put("rating", d.rating == null ? "" : d.rating);
            o.put("heat", d.heatText == null ? "" : d.heatText);
            o.put("tag", d.hotTag == null ? "" : d.hotTag);
            o.put("play", d.playCount);
            JSONArray act = new JSONArray();
            for (String a : d.actors) act.put(a);
            o.put("actors", act);
            JSONArray arr = new JSONArray();
            for (Episode e : d.episodes) {
                JSONObject x = new JSONObject();
                x.put("i", e.index);
                x.put("t", e.title);
                x.put("v", e.vid);
                x.put("d", e.durationSec);
                x.put("c", e.cover);
                arr.put(x);
            }
            o.put("eps", arr);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(detailFile(seriesId));
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static Detail loadDetail(String seriesId) {
        try {
            java.io.File f = detailFile(seriesId);
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
            if (o.optInt("v", 1) != DISK_VER) return null;   // 旧格式作废，重新拉
            if (System.currentTimeMillis() - o.optLong("ts", 0) > DISK_TTL) return null;
            Detail d = new Detail();
            d.title = o.optString("title");
            d.cover = o.optString("cover");
            d.intro = o.optString("intro");
            d.episodeCount = o.optInt("cnt");
            d.multiSeason = o.optBoolean("multi");
            d.seasonIndex = o.optInt("si");
            String rel = o.optString("rel");
            d.relatedAlbumId = rel == null || rel.isEmpty() ? null : rel;
            d.payType = o.optInt("pay", -1);
            d.rating = o.optString("rating");
            d.heatText = o.optString("heat");
            d.hotTag = o.optString("tag");
            d.playCount = o.optLong("play");
            JSONArray act = o.optJSONArray("actors");
            if (act != null) {
                for (int i = 0; i < act.length(); i++) {
                    String a = act.optString(i, "");
                    if (!a.isEmpty()) d.actors.add(a);
                }
            }
            JSONArray arr = o.optJSONArray("eps");
            if (arr == null || arr.length() == 0) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                Episode e = new Episode();
                e.index = x.optInt("i");
                e.title = x.optString("t");
                e.vid = x.optString("v");
                e.durationSec = x.optInt("d");
                e.cover = x.optString("c");
                if (!e.vid.isEmpty()) d.episodes.add(e);
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 播放地址

    /** device_level=4 → 全档位标准 H.265（必须，否则低档位是 ByteVC2 私有编码） */
    public static List<Gear> play(String vid, int deviceLevel) throws Exception {
        String ck = "play:" + vid + ":" + deviceLevel;
        List<Gear> cached = cacheGet(ck);
        if (cached != null) return cached;

        JSONObject biz = new JSONObject();
        biz.put("video_platform", 1024);          // VideoPlatformType.Mixed —— 唯一被接受的取值
        biz.put("need_all_video_definition", true);
        biz.put("need_mp4_align", true);
        biz.put("use_os_player", false);
        biz.put("device_level", deviceLevel);

        JSONObject body = new JSONObject();
        body.put("video_id", vid);
        body.put("biz_param", biz);
        body.put("video_content_type", 1);        // VideoContentType.ShortSeriesPlay

        JSONObject j = callRetry("/novel/player/multi_video_model/v1/", body, null);
        if (j.optInt("code", -1) != 0) throw new IllegalStateException("取播放信息失败");
        JSONObject data = j.optJSONObject("data");
        JSONObject node = data == null ? null : data.optJSONObject(vid);
        if (node == null) throw new IllegalStateException("播放节点为空");

        JSONObject vm = new JSONObject(node.optString("video_model", "{}"));
        JSONArray list = vm.optJSONArray("video_list");
        List<Gear> out = new ArrayList<>();
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null) continue;
            JSONObject m = v.optJSONObject("video_meta");
            JSONObject ei = v.optJSONObject("encrypt_info");
            Gear g = new Gear();
            g.definition = m == null ? "" : optStr(m, "definition", "");
            g.codec = m == null ? "" : m.optString("codec_type", "");
            g.fps = m == null ? 0 : (float)m.optDouble("fps", 0);
            g.width = m == null ? 0 : m.optInt("vwidth", 0);
            g.height = m == null ? 0 : m.optInt("vheight", 0);
            g.size = m == null ? 0 : m.optLong("size", 0);
            g.mainUrl = v.optString("main_url", "");
            g.backupUrl = v.optString("backup_url", "");
            g.encrypted = ei != null && ei.optBoolean("encrypt", false);
            g.spadeA = ei == null ? null : ei.optString("spade_a", null);
            g.kid = ei == null ? null : ei.optString("kid", null);
            if (!g.mainUrl.isEmpty()) out.add(g);
        }
        cachePut(ck, out, 5 * 60 * 1000L);
        return out;
    }

    /** 按用户偏好 / 设备上限挑档 */
    public static Gear pickGear(List<Gear> gears, String prefer, int maxShortSide) {
        if (gears == null || gears.isEmpty()) return null;
        int limit = maxShortSide > 0 ? maxShortSide : 1080;
        if (prefer != null) {
            for (Gear g : gears) {
                if (prefer.equals(g.definition) && g.shortSide() > 0
                        && g.shortSide() <= limit) return g;
            }
        }
        Gear best = null;
        for (Gear g : gears) {
            int ss = g.shortSide();
            if (ss <= 0 || ss > limit) continue;
            if (best == null || ss > best.shortSide()) best = g;
        }
        return best;
    }

    private static String optStr(JSONObject o, String key, String def) {
        String v = o.optString(key, null);
        return v == null || v.isEmpty() ? def : v;
    }
}
