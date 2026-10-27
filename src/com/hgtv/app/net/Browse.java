package com.hgtv.app.net;

import com.hgtv.app.model.Series;
import org.json.*;
import java.util.*;

/** Server cursors and selector IDs, shared by TV and the LAN companion. */
public final class Browse {
    private static volatile JSONArray sharedFilters;
    private static final java.util.concurrent.atomic.AtomicBoolean warming=new java.util.concurrent.atomic.AtomicBoolean();
    public static synchronized JSONArray cachedFilters(){
        if(sharedFilters==null)try{sharedFilters=new JSONArray(com.hgtv.app.App.prefs().getString("browse_filters_v1","[]"));}catch(Exception ignored){sharedFilters=new JSONArray();}
        return sharedFilters;
    }
    public static void preloadFilters(){
        cachedFilters();
        if(!warming.compareAndSet(false,true))return;
        com.hgtv.app.App.io().execute(()->{try{Result result=page(Repo.BOARD_RECOMMEND,"",0,UUID.randomUUID().toString());filters(result.selector,Repo.BOARD_RECOMMEND);}catch(Exception ignored){}finally{warming.set(false);}});
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
        JSONObject root=HgApi.call("/reading/bookapi/bookmall/cell/change/v",null,q);
        JSONObject data=root.optJSONObject("data");
        JSONObject cell=data==null?null:data.optJSONObject("cell_view");
        if(root.optBoolean("_empty") || root.optInt("code",0)!=0 || cell==null)
            throw new java.io.IOException(root.optString("message","推荐加载失败"));
        Result result=new Result();
        result.next=cell.optInt("next_offset",offset);result.more=cell.optBoolean("has_more") && result.next>offset;
        result.session=cell.optString("session_id",session);result.selector=cell.optJSONObject("cell_selector");
        if(result.selector!=null){categorySelector=result.selector;com.hgtv.app.App.prefs().edit().putString("category_selector_v2",result.selector.toString()).apply();}
        collect(cell.optJSONArray("cell_data"),result.items,new HashSet<>());
        if(!result.items.isEmpty())Catalog.merge(result.items);
        return result;
    }
    private static volatile JSONObject categorySelector;
    public static JSONObject categoryOptions(String type)throws Exception{
        if(categorySelector==null)try{categorySelector=new JSONObject(com.hgtv.app.App.prefs().getString("category_selector_v2","{}"));}catch(Exception ignored){}
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
            if(!encoded.equals(sharedFilters.toString()))com.hgtv.app.App.prefs().edit().putString("browse_filters_v1",encoded).apply();
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
        JSONObject response=HgApi.call("/reading/ugc/novel_comment/"+(episodeId==null?"book":"item")+"/v1/",null,q);
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
            String title=v.optString("series_title",v.optString("title",""));
            if(!sid.isEmpty() && !"0".equals(sid) && !title.isEmpty() && seen.add(sid)) {
                Series s=new Series();s.seriesId=sid;s.title=title;
                s.cover=v.optString("cover",v.optString("series_cover",""));
                s.firstVid=v.optString("vid",null);s.episodeCount=v.optInt("episode_cnt");
                s.playCount=v.optLong("play_cnt",v.optLong("series_play_cnt"));
                s.intro=v.optString("video_desc",v.optString("series_intro",""));
                s.category=v.optString("category_schema","");out.add(s);
            }
            Iterator<String> keys=v.keys();
            while(keys.hasNext()) {String k=keys.next();Object child=v.opt(k);
                if(child instanceof JSONArray || child instanceof JSONObject)collect(child,out,seen);
            }
        }
    }
}
