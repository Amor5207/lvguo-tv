package ink.pengfei.lvguo.net;

import ink.pengfei.lvguo.model.Series;
import org.json.*;
import java.util.*;

/** Server cursors and selector IDs, shared by TV and the LAN companion. */
public final class Browse {
    static final String BOOKMALL_CELL_CHANGE_PATH = "/reading/bookapi/bookmall/cell/change/v1/";
    static final String BOOKMALL_TAB_PATH = "/reading/bookapi/bookmall/tab/v1/";
    private static volatile JSONArray sharedFilters;
    private static final java.util.concurrent.atomic.AtomicBoolean warming=new java.util.concurrent.atomic.AtomicBoolean();
    public static synchronized JSONArray cachedFilters(){
        if(sharedFilters==null)try{sharedFilters=new JSONArray(ink.pengfei.lvguo.App.prefs().getString("browse_filters_v1","[]"));}catch(Exception ignored){sharedFilters=new JSONArray();}
        return sharedFilters;
    }
    public static void preloadFilters(){
        cachedFilters();
        if(!warming.compareAndSet(false,true))return;
        ink.pengfei.lvguo.App.io().execute(()->{try{Result result=page(Repo.BOARD_RECOMMEND,"",0,UUID.randomUUID().toString());filters(result.selector,Repo.BOARD_RECOMMEND);}catch(Exception ignored){}finally{warming.set(false);}});
    }
    private Browse(){}
    public static final class Result {
        public final List<Series> items=new ArrayList<>();
        public int next;
        public boolean more;
        public String session="";
        public JSONObject selector;
    }
    public static Result page(String board,String filter,int offset,String session) throws Exception {return page("all",board,filter,offset,session);}
    private static Result page(String type,String board,String filter,int offset,String session) throws Exception {
        Map<String,String> q=new LinkedHashMap<>();
        q.put("cell_id",Repo.DEFAULT_CELL);q.put("tab_type","26");
        q.put("client_req_type","2");q.put("client_template","2");q.put("screen_width_px","1350");
        q.put("selected_items",type);q.put("sub_selected_items",board);
        q.put("panel_selected_items",filter==null?"":filter);
        q.put("offset",String.valueOf(offset));q.put("session_uuid",session==null?UUID.randomUUID().toString():session);
        JSONObject root=LvguoApi.call(BOOKMALL_CELL_CHANGE_PATH,null,q);
        JSONObject data=root.optJSONObject("data");
        JSONObject cell=data==null?null:data.optJSONObject("cell_view");
        if(root.optBoolean("_empty") || root.optInt("code",0)!=0 || cell==null)
            throw new java.io.IOException(root.optString("message","推荐加载失败"));
        Result result=new Result();
        result.next=cell.optInt("next_offset",offset);result.more=cell.optBoolean("has_more") && result.next>offset;
        result.session=cell.optString("session_id",session);result.selector=cell.optJSONObject("cell_selector");
        if(result.selector!=null){categorySelector=result.selector;ink.pengfei.lvguo.App.prefs().edit().putString("category_selector_v2",result.selector.toString()).apply();}
        collect(cell.optJSONArray("cell_data"),result.items,new HashSet<>());
        if(!result.items.isEmpty())Catalog.merge(result.items);
        return result;
    }
    // ------------------------------------------------------------ 为你推荐（登录账号的个性化推荐流）

    /** 红果「推荐」频道的固定栏目 / 方案 ID（tab_type=16；同 aston314/hongguo-tv）。 */
    static final String FEED_CELL = "7294257141911650341", FEED_PLAN = "7294257258253254693";
    public static final String CH_RECOMMEND = "16";
    private static final String[][] FEED_LABELS = {{"16", "推荐"}, {"36", "漫剧"}, {"39", "真人剧"}};
    /** 每个频道的翻页状态：{栏目ID, 方案ID, session}。 */
    private static final Map<String, String[]> feedState = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile List<String[]> feedChannels;
    private static volatile long feedChannelsAt;

    /** 频道（漫剧 / 真人剧）是红果 7.1.9 起才下发的：推荐流请求按该版本号发，其余接口不变。 */
    private static Map<String,String> feedQuery() {
        Map<String,String> q = new LinkedHashMap<>();
        q.put("version_code", "71932"); q.put("version_name", "7.1.9.32"); q.put("manifest_version_code", "71932");
        q.put("update_version_code", "71932"); q.put("pv_player", "71932");
        return q;
    }
    private static Map<String,String> bookmallQuery(String channel) {
        Map<String,String> q = feedQuery();
        q.put("bottom_tab_type", "7"); q.put("landing_bottom_tab_type", "7"); q.put("need_personal_recommend", "1");
        q.put("device_level", "3"); q.put("screen_width_px", "1078"); q.put("is_horizontal_screen", "false");
        q.put("disable_digg_stat", "false"); q.put("auth_aweme", "true"); q.put("auth_backward", "true");
        q.put("has_video_cache", "false"); q.put("last_tab_type", "0"); q.put("last_tab_index", "0"); q.put("tab_index", "0");
        q.put("req_rank_category_id", "0"); q.put("video_type_preferences_str", "[]"); q.put("tab_type", channel);
        q.put("offset", "0"); q.put("session_id", ""); q.put("client_req_type", "1");
        return q;
    }

    /** 当前账号开放的推荐频道（{id, 名称}；红果按账号下发，不是每个账号都有全部频道）。缓存 30 分钟，失败只回「推荐」。 */
    public static List<String[]> feedChannels() {
        List<String[]> cached = feedChannels;
        if (cached != null && System.currentTimeMillis() - feedChannelsAt < 30 * 60_000L) return cached;
        List<String[]> out = new ArrayList<>(); out.add(FEED_LABELS[0]);
        try {
            JSONObject root = Account.signedGet(Account.APP_HOST, BOOKMALL_TAB_PATH, bookmallQuery(CH_RECOMMEND));
            JSONObject data = root.optJSONObject("data"); JSONArray tabs = data == null ? null : data.optJSONArray("tab_item");
            Set<String> have = new HashSet<>();
            for (int i = 0; tabs != null && i < tabs.length(); i++) { JSONObject t = tabs.optJSONObject(i); if (t != null) have.add(t.optString("tab_type")); }
            for (int i = 1; i < FEED_LABELS.length; i++) if (have.contains(FEED_LABELS[i][0])) out.add(FEED_LABELS[i]);
            feedChannels = out; feedChannelsAt = System.currentTimeMillis();
        } catch (Exception e) { android.util.Log.w("ForYou", "channels failed", e); }
        return out;
    }

    public static Result forYou(int offset) throws Exception { return forYou(CH_RECOMMEND, offset); }

    /**
     * 红果首页的推荐流（按登录账号个性化，每次请求内容都会变）。channel：16 推荐 / 36 漫剧 / 39 真人剧。
     * 「推荐」直接用固定栏目走签名版 cell/change；其它频道第一页（first=true）先走 bookmall/tab 拿到首屏和该频道的
     * 栏目 / 方案 ID，之后用 cell/change 从 0 翻页。
     */
    public static Result forYou(String channel, int offset) throws Exception { return forYou(channel, offset, false); }
    public static Result forYou(String channel, int offset, boolean first) throws Exception {
        if (channel == null || channel.isEmpty()) channel = CH_RECOMMEND;
        Result result = new Result();
        String[] st = feedState.get(channel);
        if (CH_RECOMMEND.equals(channel)) { if (st == null || first) st = new String[]{FEED_CELL, FEED_PLAN, ""}; }
        else if (st == null || first) {
            JSONObject root = Account.signedGet(Account.APP_HOST, BOOKMALL_TAB_PATH, bookmallQuery(channel));
            JSONObject data = root.optJSONObject("data"); JSONArray tabs = data == null ? null : data.optJSONArray("tab_item");
            for (int i = 0; tabs != null && i < tabs.length(); i++) {
                JSONObject t = tabs.optJSONObject(i); if (t == null || !channel.equals(t.optString("tab_type"))) continue;
                JSONArray cells = t.optJSONArray("cell_data"); String cell = "";
                collect(cells, result.items, new HashSet<>());
                dramasOnly(result.items);
                for (int k = 0; cells != null && k < cells.length() && cell.isEmpty(); k++) {
                    JSONObject c = cells.optJSONObject(k); if (c == null) continue;
                    String id = c.optString("cell_id_str", c.optString("cell_id"));
                    if (isId(id)) cell = id;
                }
                String plan = t.optString("bookstore_id"); result.session = t.optString("session_id");
                if (cell.isEmpty() || !isId(plan)) result.more = false;
                else { feedState.put(channel, new String[]{cell, plan, result.session}); result.next = 0; result.more = true; }
                if (!result.items.isEmpty()) Catalog.merge(result.items);
                return result;
            }
            throw new java.io.IOException("推荐加载失败");
        }
        Map<String,String> q = feedQuery();
        q.put("bottom_tab_type", "7"); q.put("client_req_type", "2"); q.put("client_template", "7");
        q.put("change_type", "0"); q.put("category_id", "0"); q.put("cell_sub_id", "0"); q.put("limit", "0");
        q.put("need_personal_recommend", "1"); q.put("video_type_preferences_str", "[]");
        q.put("cell_id", st[0]); q.put("plan_id", st[1]); q.put("tab_type", channel);
        q.put("offset", String.valueOf(Math.max(0, offset))); q.put("session_id", st[2]); q.put("filter_ids", "");
        JSONObject root = Account.signedGet(Account.APP_HOST, BOOKMALL_CELL_CHANGE_PATH, q);
        JSONObject data = root.optJSONObject("data");
        JSONObject cv = data == null ? null : data.optJSONObject("cell_view");
        if (root.optInt("code", -1) != 0 || cv == null) throw new java.io.IOException("推荐加载失败");
        collect(cv.opt("cell_data"), result.items, new HashSet<>());
        String sess = data.optString("session_id", st[2]);
        feedState.put(channel, new String[]{st[0], st[1], sess});
        result.session = sess; result.next = data.optInt("next_offset", offset);
        result.more = data.optBoolean("has_more") && result.next > offset && !result.items.isEmpty();
        dramasOnly(result.items);
        if (!result.items.isEmpty()) Catalog.merge(result.items);
        return result;
    }
    /** 去掉推荐流里混入的非剧集内容（公益提示、运营位等：没有封面或没有集数）。 */
    private static void dramasOnly(List<Series> items) {
        for (Iterator<Series> it = items.iterator(); it.hasNext(); ) { Series s = it.next(); if (s.cover == null || s.cover.isEmpty() || s.episodeCount <= 0) it.remove(); }
    }
    private static boolean isId(String s) {
        if (s == null || s.isEmpty() || "0".equals(s)) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private static volatile JSONObject categorySelector;
    public static JSONObject categoryOptions(String type)throws Exception{
        if(categorySelector==null)try{categorySelector=new JSONObject(ink.pengfei.lvguo.App.prefs().getString("category_selector_v2","{}"));}catch(Exception ignored){}
        JSONObject selector=categorySelector;
        if(selector==null||selector.optJSONObject("outer_row")==null){page("all",Repo.BOARD_RECOMMEND,"",0,null);selector=categorySelector;}
        JSONArray types=new JSONArray(),boards=new JSONArray(),rows=new JSONArray();JSONObject top=selector==null?null:selector.optJSONObject("outer_row");JSONArray outer=top==null?null:top.optJSONArray("items");
        for(int n=0;outer!=null&&n<outer.length();n++){JSONObject item=outer.optJSONObject(n);if(item==null)continue;String id=item.optString("selector_item_id");if(!Arrays.asList("all","human","comic_series_rank","ai_playlet").contains(id))continue;
            types.put(new JSONObject().put("id",id).put("label",item.optString("show_name")));
            if(!id.equals(type))continue;JSONObject sub=item.optJSONObject("sub_cell_selector"),boardRow=sub==null?null:sub.optJSONObject("outer_row");JSONArray items=boardRow==null?null:boardRow.optJSONArray("items");
            for(int k=0;items!=null&&k<items.length();k++){JSONObject b=items.optJSONObject(k);if(b==null)continue;JSONArray values=new JSONArray();JSONObject panel=b.optJSONObject("panel_selector");JSONArray groups=panel==null?null:panel.optJSONArray("inner_rows");
                for(int r=0;groups!=null&&r<groups.length();r++){JSONObject group=groups.optJSONObject(r);if(group==null)continue;JSONArray options=group.optJSONArray("items"),opts=new JSONArray();
                    for(int z=0;options!=null&&z<options.length();z++){JSONObject v=options.optJSONObject(z);if(v!=null&&!v.optString("selector_item_id").isEmpty())opts.put(new JSONObject().put("id",v.optString("selector_item_id")).put("label",v.optString("show_name")));}
                    values.put(new JSONObject().put("name",group.optString("row_name")).put("items",opts));
                }
                boards.put(new JSONObject().put("id",b.optString("selector_item_id")).put("label",b.optString("show_name")).put("groups",values));
            }
        }
        return new JSONObject().put("types",types).put("boards",boards);
    }
    /** Server ranks use one anchor condition; additional groups match the returned genre metadata. */
    public static Result categoryPage(String type,String board,JSONObject selected,int offset,String session)throws Exception{
        JSONObject options=categoryOptions(type);JSONArray boards=options.getJSONArray("boards");JSONObject current=null;
        for(int i=0;i<boards.length();i++)if(board.equals(boards.getJSONObject(i).optString("id")))current=boards.getJSONObject(i);
        if(current==null){if(boards.length()==0)throw new java.io.IOException("分类暂不可用");current=boards.getJSONObject(0);board=current.getString("id");}
        LinkedHashMap<String,String> labels=new LinkedHashMap<>();JSONArray groups=current.getJSONArray("groups");
        for(int i=0;i<groups.length();i++){JSONObject group=groups.getJSONObject(i);String id=selected.optString(group.getString("name"));JSONArray items=group.getJSONArray("items");for(int k=0;k<items.length();k++){JSONObject item=items.getJSONObject(k);if(!id.isEmpty()&&id.equals(item.getString("id")))labels.put(id,item.getString("label"));}}
        String anchor="";for(String id:labels.keySet())anchor=id;for(String id:labels.keySet())if(id.startsWith("gender_"))anchor=id;
        Result out=new Result();out.next=offset;out.session=session;int pages=0;Set<String> seen=new HashSet<>();
        do{Result part=page(type,board,anchor,out.next,out.session);out.next=part.next;out.session=part.session;out.more=part.more;out.selector=part.selector;
            for(Series item:part.items){boolean matches=true;for(Map.Entry<String,String> condition:labels.entrySet()){if(condition.getKey().startsWith("gender_"))continue;if(!matchesCategory(item.category,condition.getValue())){matches=false;break;}}if(matches&&seen.add(item.seriesId))out.items.add(item);}
        }while(out.more&&out.items.size()<12&&++pages<4);
        return out;
    }
    private static boolean matchesCategory(String raw,String label){
        if(raw==null||raw.isEmpty())return false;String[] names={label};
        if(label.equals("历史古代")||label.equals("古装"))names=new String[]{"古代","古风","古装"};
        if(label.equals("校园"))names=new String[]{"校园","青春"};
        try{JSONArray tags=new JSONArray(raw);for(int i=0;i<tags.length();i++){JSONObject tag=tags.optJSONObject(i);String name=tag==null?"":tag.optString("name");for(String candidate:names)if(name.contains(candidate))return true;}}catch(Exception ignored){}
        return false;
    }
    public static JSONObject comments(String id,int offset) throws Exception {
        return comments(id,null,offset);
    }
    /** Preserve the server's global single-selection rule across its category rows. */
    public static synchronized JSONArray filters(JSONObject selector,String board)throws JSONException {
        cachedFilters();
        JSONArray rows=extractFilters(selector,board);
        if(rows.length()==0 && !Repo.BOARD_RECOMMEND.equals(board))rows=extractFilters(selector,Repo.BOARD_RECOMMEND);
        if(rows.length()>0){
            LinkedHashMap<String,JSONObject> merged=new LinkedHashMap<>();
            if(sharedFilters!=null)for(int n=0;n<sharedFilters.length();n++){JSONObject row=sharedFilters.getJSONObject(n);merged.put(row.optString("name"),row);}
            for(int n=0;n<rows.length();n++){JSONObject row=rows.getJSONObject(n);if(row.optJSONArray("items")!=null && row.getJSONArray("items").length()>0)merged.put(row.optString("name"),row);}
            JSONArray complete=new JSONArray();for(JSONObject row:merged.values())complete.put(row);
            String encoded=complete.toString();
            if(!encoded.equals(sharedFilters.toString()))ink.pengfei.lvguo.App.prefs().edit().putString("browse_filters_v1",encoded).apply();
            sharedFilters=complete;return complete;
        }
        return sharedFilters==null?new JSONArray():sharedFilters;
    }
    private static JSONArray extractFilters(JSONObject selector,String board)throws JSONException {
        JSONArray result=new JSONArray();if(selector==null)return result;
        JSONObject outer=selector.optJSONObject("outer_row");JSONArray items=outer==null?null:outer.optJSONArray("items");
        if(items==null)return result;
        for(int i=0;i<items.length();i++){
            JSONObject top=items.optJSONObject(i),sub=top==null?null:top.optJSONObject("sub_cell_selector");
            JSONObject row=sub==null?null:sub.optJSONObject("outer_row");JSONArray boards=row==null?null:row.optJSONArray("items");
            if(boards==null)continue;
            for(int n=0;n<boards.length();n++){
                JSONObject entry=boards.optJSONObject(n);if(entry==null || !board.equals(entry.optString("selector_item_id")))continue;
                JSONObject panel=entry.optJSONObject("panel_selector");JSONArray rows=panel==null?null:panel.optJSONArray("inner_rows");
                if(rows==null)return result;
                for(int r=0;r<rows.length();r++){
                    JSONObject group=rows.optJSONObject(r);if(group==null)continue;JSONArray options=group.optJSONArray("items"),values=new JSONArray();
                    if(options!=null)for(int o=0;o<options.length();o++){JSONObject option=options.optJSONObject(o);if(option!=null)values.put(new JSONObject().put("id",option.optString("selector_item_id")).put("label",option.optString("show_name")));}
                    result.put(new JSONObject().put("name",group.optString("row_name","题材")).put("items",values));
                }
            }
        }
        return result;
    }
    public static JSONObject comments(String id,String episodeId,int offset) throws Exception {
        Map<String,String> q=new LinkedHashMap<>();q.put("book_id",id);q.put("group_id",id);
        if(episodeId!=null){q.put("item_id",episodeId);q.put("group_id",episodeId);}
        q.put("offset",String.valueOf(offset));q.put("count","20");q.put("sort","1");
        JSONObject response=LvguoApi.call("/reading/ugc/novel_comment/"+(episodeId==null?"book":"item")+"/v1/",null,q);
        JSONObject data=response.optJSONObject("data");
        if(response.optBoolean("_empty") || response.optInt("code",-1)!=0 || data==null)throw new java.io.IOException("评论加载未完成");
        return data;
    }
    /** UGC may carry a text body, rich segments, or an image-only body. */
    public static String commentText(JSONObject row){
        for(String key:new String[]{"text","content","comment_text","comment_content"}){
            String text=readCommentText(row.opt(key));if(!text.trim().isEmpty())return text;
        }return "";
    }
    private static String readCommentText(Object value){
        if(value==null||value==JSONObject.NULL)return "";
        if(value instanceof String){String text=(String)value;String trimmed=text.trim();
            try{if(trimmed.startsWith("{"))return readCommentText(new JSONObject(trimmed));if(trimmed.startsWith("[{") )return readCommentText(new JSONArray(trimmed));}catch(JSONException ignored){}
            return text;
        }
        if(value instanceof JSONArray){StringBuilder text=new StringBuilder();JSONArray parts=(JSONArray)value;for(int i=0;i<parts.length();i++)text.append(readCommentText(parts.opt(i)));return text.toString();}
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String key:new String[]{"text","content","description"}){String text=readCommentText(object.opt(key));if(!text.trim().isEmpty())return text;}}
        return "";
    }
    public static JSONArray commentImages(JSONObject row){
        JSONArray out=new JSONArray();java.util.Set<String> seen=new java.util.LinkedHashSet<>();
        for(String key:new String[]{"image_list","images","image","sticker","image_info"})collectCommentImages(row.opt(key),seen,0);
        for(String url:seen)out.put(url);return out;
    }
    private static void collectCommentImages(Object value,java.util.Set<String> out,int depth){
        if(depth>5||value==null||out.size()>=9)return;
        if(value instanceof String){String url=(String)value;if(url.startsWith("https://")||url.startsWith("http://"))out.add(url);}
        else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)collectCommentImages(array.opt(i),out,depth+1);}
        else if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String key:new String[]{"url_list","url","image_url","origin_url","origin_image","large","thumb"}){int before=out.size();collectCommentImages(object.opt(key),out,depth+1);if(out.size()>before)break;}}
    }
    public static List<Series> search(String query) throws Exception {
        return OnlineSearch.page(query,1).items;
    }

    public static void collect(Object value,List<Series> out,Set<String> seen) {
        if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++)collect(array.opt(i),out,seen);
        }else if(value instanceof JSONObject) {
            JSONObject v=(JSONObject)value;
            String sid=v.optString("series_id","");
            String title=v.optString("series_title",v.optString("series_name",v.optString("title","")));
            if(!sid.isEmpty() && !"0".equals(sid) && !title.isEmpty() && seen.add(sid)) {
                Series s=new Series();s.seriesId=sid;s.title=title;
                s.cover=v.optString("cover",v.optString("series_cover",""));
                s.firstVid=v.optString("vid",null);s.episodeCount=v.optInt("episode_cnt");
                s.playCount=v.optLong("play_cnt",v.optLong("series_play_cnt"));
                s.intro=v.optString("video_desc",v.optString("series_intro",""));
                s.category=categoryNames(v.optString("category_schema",""));out.add(s);
            }
            Iterator<String> keys=v.keys();
            while(keys.hasNext()) {String k=keys.next();Object child=v.opt(k);
                if(child instanceof JSONArray || child instanceof JSONObject)collect(child,out,seen);
            }
        }
    }

    /** category_schema 是 JSON 数组字符串（[{"name":"脑洞",...}]），转成「脑洞,重生」这样的纯文本；旧缓存里的原始值也走这里 */
    public static String categoryNames(String raw){
        if(raw==null)return "";String t=raw.trim();
        if(!t.startsWith("["))return t;
        try{org.json.JSONArray a=new org.json.JSONArray(t);StringBuilder sb=new StringBuilder();
            for(int i=0;i<a.length();i++){Object o=a.opt(i);String n=o instanceof org.json.JSONObject?((org.json.JSONObject)o).optString("name",""):String.valueOf(o);
                n=n.trim();if(!n.isEmpty()&&!"null".equals(n)){if(sb.length()>0)sb.append(',');sb.append(n);}}
            return sb.toString();
        }catch(Exception e){return "";}
    }
}
