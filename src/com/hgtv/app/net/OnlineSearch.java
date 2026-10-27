package com.hgtv.app.net;

import com.hgtv.app.model.Series;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Uses the official website's current JSON search and suggestion endpoints. */
public final class OnlineSearch {
    private OnlineSearch() {}
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
            JSONObject suggestions=new JSONObject(read("/api/search/suggestion?query="+URLEncoder.encode(query,"UTF-8")+"&count=50"));
            JSONArray rows=suggestions.optJSONArray("suggestionList");
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
    public static final class Page {
        public final List<Series> items = new ArrayList<>();
        public int number, total;
        public boolean more;
    }
    public static Page page(String query, int number) throws Exception {
        query = java.text.Normalizer.normalize(query == null ? "" : query.trim(), java.text.Normalizer.Form.NFKC);
        if(query.isEmpty() || query.length()>100)throw new IOException("请输入剧名，最多100字");
        number=Math.max(1,number);
        String path="/api/search/result?query="+URLEncoder.encode(query,"UTF-8")+(number>1?"&page="+number:"");
        JSONObject data;
        try{data=new JSONObject(read(path));}
        catch(IOException error){
            if(number!=1)throw error;
            JSONObject suggestions=new JSONObject(read("/api/search/suggestion?query="+URLEncoder.encode(query,"UTF-8")));
            JSONArray fallback=suggestions.optJSONArray("suggestionList");
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
