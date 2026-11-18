package ink.pengfei.lvguo.remote;

import android.app.*;
import android.content.*;
import android.os.*;
import ink.pengfei.lvguo.*;
import ink.pengfei.lvguo.model.Episode;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.*;
import ink.pengfei.lvguo.store.Store;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Local-only companion HTTP service. APIs require the current same-origin page session key. */
public final class LocalService extends Service {
    private static volatile boolean running;
    private static final int PORT_BASE=8765;
    /** 实际监听的端口：8765 被别的应用占用时依次尝试 8766…8775，二维码里用这里的实际端口。 */
    private static volatile int PORT=PORT_BASE;
    private static final String TOKEN=makeToken();
    private ServerSocket server;
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    private final ExecutorService contentWorkers=Executors.newFixedThreadPool(4);
    private static String makeToken(){byte[] bytes=new byte[20];new SecureRandom().nextBytes(bytes);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
    public static String address(){
        if(!running)return null;
        String fallback=null;
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){
            NetworkInterface it=interfaces.nextElement();if(!it.isUp()||it.isLoopback())continue;
            String name=it.getName().toLowerCase(Locale.US);if(name.startsWith("tun")||name.startsWith("tap")||name.startsWith("ppp"))continue;
            Enumeration<InetAddress> addresses=it.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress address=addresses.nextElement();if(address instanceof Inet4Address && address.isSiteLocalAddress()){
                    String value="http://"+address.getHostAddress()+":"+PORT+"/";
                    if(name.startsWith("wlan")||name.startsWith("eth"))return value;if(fallback==null)fallback=value;
                }
            }
        }}catch(Exception ignored){}return fallback;
    }
    @Override public void onCreate(){super.onCreate();
        if(Build.VERSION.SDK_INT>=26){NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("companion","手机选剧",NotificationManager.IMPORTANCE_LOW));}
        Notification.Builder notification=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"companion"):new Notification.Builder(this);
        Intent home=new Intent(this,ink.pengfei.lvguo.ui.RootActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,home,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(71,notification.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("绿果TV · 手机选剧已开启").setContentText("手机连接相同 Wi-Fi，可扫码点播到电视").setContentIntent(pending).setOngoing(true).build());
        new Thread(()->{try{IOException bindError=null;
            for(int p=PORT_BASE;p<PORT_BASE+11;p++){
                try{ServerSocket s=new ServerSocket();s.setReuseAddress(true);s.bind(new InetSocketAddress(p));server=s;PORT=p;bindError=null;break;}
                catch(IOException e){bindError=e;}
            }
            if(server==null)throw bindError==null?new IOException("没有可用端口"):bindError;
            android.util.Log.i("TVCompanion","listening on "+PORT);running=true;
            while(!server.isClosed()){Socket socket=server.accept();try{workers.execute(()->handle(socket));}catch(RejectedExecutionException e){socket.close();}}
        }catch(IOException e){android.util.Log.e("TVCompanion","Local service unavailable",e);}finally{running=false;}},"TVCompanion").start();
    }
    @Override public int onStartCommand(Intent i,int flags,int startId){return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){running=false;try{if(server!=null)server.close();}catch(IOException ignored){}workers.shutdownNow();contentWorkers.shutdownNow();super.onDestroy();}
    private static String line(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){if(b=='\n')break;if(b!='\r')out.write(b);if(out.size()>8192)throw new IOException("Header too large");}
        return out.toString("UTF-8");
    }
    private static Map<String,String> params(String query)throws Exception {
        Map<String,String> result=new HashMap<>();if(query==null)return result;
        for(String part:query.split("&")){String[] kv=part.split("=",2);result.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"");}return result;
    }
    private void handle(Socket socket){
        boolean handedOff=false;
        try{Socket client=socket;client.setSoTimeout(15000);InputStream input=client.getInputStream();String request=line(input);String[] first=request.split(" ");if(first.length<2)return;
            URI uri=new URI(first[1]);String path=uri.getPath();Map<String,String> query=params(uri.getRawQuery());Map<String,String> headers=new HashMap<>();
            int total=0;while(true){String header=line(input);total+=header.length();if(total>16384)throw new IOException("Header too large");if(header.isEmpty())break;int split=header.indexOf(':');if(split>0)headers.put(header.substring(0,split).toLowerCase(Locale.US),header.substring(split+1).trim());}
            if(path.matches("/emoji/[0-9]{1,2}\\.webp")){String number=path.substring(7,path.length()-5);int resource=getResources().getIdentifier("comment_emoji_"+number,"drawable",getPackageName());if(resource==0)throw new IOException("Image unavailable");try(InputStream image=getResources().openRawResource(resource)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=image.read(buf))!=-1)out.write(buf,0,n);respond(client,200,"image/webp",out.toByteArray());}return;}
            if("/brand-logo-v2.webp".equals(path)){try(InputStream image=getResources().openRawResource(R.drawable.logo_mark)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=image.read(buf))!=-1)out.write(buf,0,n);respond(client,200,"image/webp",out.toByteArray());}return;}
            if("/app-icon.png".equals(path)||"/app-icon-final.png".equals(path)){try(InputStream image=getResources().openRawResource(R.drawable.app_icon)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=image.read(buf))!=-1)out.write(buf,0,n);respond(client,200,"image/png",out.toByteArray());}return;}
            if("/".equals(path)){try(InputStream html=getResources().openRawResource(R.raw.companion)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=html.read(buf))!=-1)out.write(buf,0,n);String page=new String(out.toByteArray(),StandardCharsets.UTF_8).replace("__TV_SESSION__",TOKEN);respond(client,200,"text/html; charset=utf-8",page.getBytes(StandardCharsets.UTF_8));}return;}
            String method=first[0];JSONObject body=new JSONObject();
            if("POST".equals(method)){
                int length=Integer.parseInt((headers.containsKey("content-length")?headers.get("content-length"):"0"));if(length<0||length>8192)throw new IOException("Body too large");
                byte[] bytes=new byte[length];int read=0;while(read<length){int n=input.read(bytes,read,length-read);if(n<0)throw new EOFException();read+=n;}if(length>0)body=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            }
            final JSONObject payload=body;
            // ---- 手机遥控安全：总开关 → 访问密码 → 强制休息，三道检查对所有接口统一生效
            String cookie=headers.get("cookie");
            if("/api/remote/state".equals(path)){json(socket,200,remoteState(cookie));return;}
            if("/api/remote/login".equals(path)&&"POST".equals(method)){remoteLogin(socket,payload);return;}
            if(path.startsWith("/api/")){
                if(!RemoteAuth.remoteOn()){json(socket,403,new JSONObject().put("error","手机遥控已在电视上关闭").put("remoteOff",true));return;}
                if(!RemoteAuth.allowed(cookie)){json(socket,401,new JSONObject().put("error","请先输入电视上设置的遥控密码").put("needPassword",true));return;}
                if(ink.pengfei.lvguo.care.RestMode.isResting()&&!"/api/status".equals(path)){
                    json(socket,423,new JSONObject().put("error","休息时段内暂不可用").put("rest",true));return;}
            }
            final String renew=RemoteAuth.hasPassword()?RemoteAuth.renewCookie(cookie):null;   // 授权自动续期
            if(Arrays.asList("/api/home","/api/browse","/api/category/options","/api/category/page","/api/search","/api/hot","/api/suggest","/api/detail","/api/seasons","/api/comments","/api/reservations","/api/play","/api/favorite").contains(path)){
                contentWorkers.execute(()->serveApi(client,path,query,method,payload,renew));
                handedOff=true;
            }else{serveApi(client,path,query,method,payload,renew);handedOff=true;}

        }catch(Exception error){try{json(socket,500,new JSONObject().put("error",error.getMessage()==null?"请求失败，请重试":error.getMessage()));}catch(Exception ignored){}}finally{if(!handedOff)try{socket.close();}catch(IOException ignored){}}
    }
    /** 评论头像：和电视端同一套规范化（http→https、统一成公开 JPEG 版式），手机浏览器才能稳定显示真实头像。 */
    private static String avatarUrl(JSONObject user){
        if(user==null)return "";
        String raw=user.optString("user_avatar","");
        if(raw.isEmpty())raw=user.optString("expand_user_avatar","");
        if(raw.isEmpty())return "";
        String fixed=ink.pengfei.lvguo.img.ImageUrls.compatible(raw);
        return fixed==null?"":fixed;
    }
    // Upstream content requests must not occupy the status/control workers.
    private void serveApi(Socket client,String path,Map<String,String> query,String method,JSONObject body,String setCookie){
        SET_COOKIE.set(setCookie);
        try{
            JSONObject result=new JSONObject();
            if("/api/status".equals(path))result=PlaybackControl.status().put("rest",ink.pengfei.lvguo.care.RestMode.isResting());
            else if("/api/playback/diagnostics".equals(path))result=ink.pengfei.lvguo.media.PlaybackDiagnostics.snapshot();
            else if("/api/category/options".equals(path))result=Browse.categoryOptions(query.containsKey("type")?query.get("type"):"all");
            else if("/api/category/page".equals(path)){
                String type=query.containsKey("type")?query.get("type"):"all",board=query.containsKey("board")?query.get("board"):Repo.BOARD_RECOMMEND;
                JSONObject selected=new JSONObject(query.containsKey("selected")?query.get("selected"):"{}");
                Browse.Result page=Browse.categoryPage(type,board,selected,Math.max(0,Integer.parseInt(query.containsKey("offset")?query.get("offset"):"0")),query.get("session"));
                result.put("items",series(page.items)).put("offset",page.next).put("more",page.more).put("session",page.session);
            }
            else if("/api/filters".equals(path))result.put("groups",Browse.cachedFilters());
            else if("/api/history/clear".equals(path)&&"POST".equals(method)){long id=Store.clearHistory();if(id<0)throw new IOException("账号已切换，请重试");result.put("operation",id);}
            else if("/api/history/clear/status".equals(path)){long id=Long.parseLong(query.containsKey("operation")?query.get("operation"):"-1");result.put("status",ink.pengfei.lvguo.net.LibrarySync.historyClearStatus(id));}
            else if("/api/profile".equals(path)){
                // 账号资料：手机「我的」页顶部展示（头像统一规范化成浏览器能显示的 JPEG）
                ink.pengfei.lvguo.net.Session.Profile pf=ink.pengfei.lvguo.net.Session.profile();
                String av=pf.avatar==null?"":ink.pengfei.lvguo.img.ImageUrls.compatible(pf.avatar);
                result.put("loggedIn",ink.pengfei.lvguo.net.Session.loggedIn()).put("name",pf.name).put("avatar",av==null?"":av).put("signature",pf.signature)
                      .put("vip",pf.vip).put("watchHours",pf.watchMs/3600000L).put("activeDays",pf.activeDays)
                      .put("history",Store.history().size()).put("favorites",Store.favorites().size());
            }
            else if("/api/tv/settings".equals(path)){
                // 电视设置（手机设置页按这份描述渲染，和电视「设置」保持一致）
                result="POST".equals(method)?TvSettings.set(body.optString("key"),body.opt("value")):TvSettings.all();
            }
            else if("/api/library".equals(path)){
                String kind=query.containsKey("kind")?query.get("kind"):"history";
                if(!"history".equals(kind)&&!"favorites".equals(kind))throw new IOException("书架分类不存在");
                List<Store.Item> all="history".equals(kind)?Store.history():Store.favorites();
                int offset=Math.min(all.size(),Math.max(0,Integer.parseInt(query.containsKey("offset")?query.get("offset"):"0")));
                int end=Math.min(all.size(),offset+30);JSONArray items=new JSONArray();LibrarySnapshot library=new LibrarySnapshot();
                for(int n=offset;n<end;n++){Store.Item item=all.get(n);items.put(library.apply(new JSONObject().put("id",item.seriesId).put("title",item.title).put("cover",ink.pengfei.lvguo.img.ImageUrls.compatible(item.cover)).put("count",item.episodeCount)));}
                result.put("items",items).put("offset",end).put("more",end<all.size()).put("total",all.size());
            }
            else if("/api/reservations".equals(path)){
                Account.Reservations online=Account.reservations(true),offline=Account.reservations(false);
                JSONArray items=new JSONArray();LibrarySnapshot library=new LibrarySnapshot();
                for(Account.Entry entry:online.items)items.put(reservationItem(entry,library));
                for(Account.Entry entry:offline.items)items.put(reservationItem(entry,library));
                int onlineCount=Math.max(online.onlineTotal,online.items.size());
                int offlineCount=Math.max(offline.offlineTotal,offline.items.size());
                result.put("items",items).put("total",onlineCount+offlineCount)
                    .put("onlineTotal",onlineCount).put("offlineTotal",offlineCount);
            }
            else if("/api/home".equals(path) || "/api/browse".equals(path)){
                String board=query.containsKey("board")?query.get("board"):Repo.BOARD_RECOMMEND;
                if(!Arrays.asList(Repo.BOARD_RECOMMEND,Repo.BOARD_HOT,Repo.BOARD_NEW).contains(board))throw new IOException("榜单不存在");
                String filter=query.containsKey("filter")?query.get("filter"):"";
                if(!filter.matches("(?:|cate_[0-9]{1,8}|gender_female|gender_male)"))throw new IOException("分类条件无效");
                int offset=Math.max(0,Integer.parseInt(query.containsKey("offset")?query.get("offset"):"0"));
                Browse.Result page=Browse.page(board,filter,offset,query.get("session"));
                result.put("items",series(page.items)).put("offset",page.next).put("more",page.more).put("session",page.session);
                if("/api/browse".equals(path))result.put("groups",Browse.filters(page.selector,board));
            }else if("/api/hot".equals(path)){
                // 热搜榜：手机搜索页的「大家在搜」
                result.put("items",series(ink.pengfei.lvguo.net.Repo.rank("ranklist_hot_search_sc",null,20)));
            }else if("/api/suggest".equals(path)){
                String q=(query.containsKey("q")?query.get("q"):"").trim();
                result.put("words",new JSONArray(ink.pengfei.lvguo.net.OnlineSearch.suggest(q)));
            }else if("/api/search".equals(path)){
                String q=(query.containsKey("q")?query.get("q"):"").trim();if(q.isEmpty()||q.length()>100)throw new IOException("请输入剧名，最多100字");
                ink.pengfei.lvguo.net.OnlineSearch.Page found=ink.pengfei.lvguo.net.OnlineSearch.page(q,Math.max(1,Integer.parseInt(query.containsKey("page")?query.get("page"):"1")));
                result.put("items",series(found.items)).put("total",found.total).put("offset",found.number+1).put("more",found.more).put("source","official");
            }else if("/api/detail".equals(path)){
                String id=id(query.get("id"));Repo.Detail detail=Repo.detail(id);JSONArray eps=new JSONArray();
                for(int n=0;n<detail.episodes.size();n++)eps.put(new JSONObject().put("index",n).put("number",detail.episodes.get(n).index));
                String cover=seriesCover(id,detail);
                result.put("id",id).put("title",detail.title).put("intro",detail.intro).put("cover",ink.pengfei.lvguo.img.ImageUrls.compatible(cover)).put("episodes",eps).put("multiSeason",detail.multiSeason).put("season",detail.seasonIndex);
                // 遥控页专辑旁的信息：评分 / 热度 / 运营标签 / 免费 / 题材 / 主演
                String category="";List<Series> cachedAll=Catalog.cached();if(cachedAll!=null)for(Series it:cachedAll)if(id.equals(it.seriesId)&&it.category!=null){category=it.category;break;}
                JSONArray actors=new JSONArray();for(int n=0;n<Math.min(3,detail.actors.size());n++)actors.put(detail.actors.get(n));
                result.put("rating",detail.rating==null?"":detail.rating).put("heat",detail.heatText==null?"":detail.heatText).put("hotTag",detail.hotTag==null?"":detail.hotTag)
                      .put("free",detail.free()).put("category",ink.pengfei.lvguo.net.Browse.categoryNames(category)).put("actors",actors);
                new LibrarySnapshot().apply(result);
            }else if("/api/comments".equals(path)){
                String id=id(query.get("id")),episodeId=null;
                int number=Integer.parseInt(query.containsKey("episode")?query.get("episode"):"0");
                if(number>0){Repo.Detail detail=Repo.detail(id);for(ink.pengfei.lvguo.model.Episode ep:detail.episodes)if(ep.index==number){episodeId=ep.vid;break;}if(episodeId==null)throw new IOException("集数不存在");}
                int offset=Math.max(0,Integer.parseInt(query.containsKey("offset")?query.get("offset"):"0"));
                JSONObject data=Browse.comments(id,episodeId,offset);JSONArray rows=data.optJSONArray("comment"),items=new JSONArray();
                if(rows!=null)for(int n=0;n<rows.length();n++){JSONObject row=rows.optJSONObject(n);if(row==null)continue;JSONObject user=row.optJSONObject("user_info");items.put(new JSONObject().put("id",row.optString("comment_id")).put("name",user==null?"观众":user.optString("user_name","观众")).put("avatar",avatarUrl(user)).put("text",Browse.commentText(row)).put("images",Browse.commentImages(row)).put("likes",row.optLong("digg_count")));}
                result.put("items",items).put("total",data.optLong("comment_cnt")).put("offset",data.optInt("next_offset",offset)).put("more",data.optBoolean("has_more"));
            }else if("/api/seasons".equals(path)){
                String id=id(query.get("id"));Repo.Detail detail=Repo.detail(id);JSONArray seasons=new JSONArray();
                if(detail.multiSeason)for(Repo.Season season:Repo.seasons(id,detail.relatedAlbumId))
                    seasons.put(new JSONObject().put("id",season.id).put("title",season.title).put("cover",season.cover).put("number",season.number).put("count",season.episodes));
                result.put("items",seasons);
            }else if("/api/favorite".equals(path) && "POST".equals(method)){
                String id=id(body.optString("id"));boolean enabled=body.optBoolean("enabled");
                Repo.Detail detail=Repo.detail(id);String cover=Store.usableCover(body.optString("cover"))?body.optString("cover"):seriesCover(id,detail);
                Store.setFavorite(id,detail.title,cover,detail.episodes.isEmpty()?null:detail.episodes.get(0).vid,enabled);
                result.put("ok",true).put("favorite",enabled);
            }else if("/api/play".equals(path) && "POST".equals(method)){
                String id=id(body.optString("id"));int index=Math.max(0,body.optInt("index"));Repo.Detail detail=Repo.detail(id);
                int position=0;
                if(body.optBoolean("resume")){
                    JSONObject active=PlaybackControl.status();
                    if(active.optBoolean("playing") && !active.optBoolean("ended") && id.equals(active.optString("id"))){
                        result=PlaybackControl.command("play",new JSONObject());json(client,200,result);return;
                    }
                    Store.Item saved=Store.watched(id);
                    if(saved!=null){index=Math.min(Math.max(0,saved.episodeIndex),Math.max(0,detail.episodes.size()-1));position=saved.positionMs;
                        if(saved.durationMs>0 && position>=saved.durationMs-1000){position=0;if(index+1<detail.episodes.size())index++;}
                    }
                }
                if(index>=detail.episodes.size())throw new IOException("集数超出范围");
                Episode selected=detail.episodes.get(index);
                Intent play=new Intent(this,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                play.putExtra(PlayerActivity.EXTRA_SERIES,id).putExtra(PlayerActivity.EXTRA_TITLE,detail.title).putExtra(PlayerActivity.EXTRA_INDEX,index);
                play.putExtra(PlayerActivity.EXTRA_VID,selected.vid).putExtra(PlayerActivity.EXTRA_EPISODE_NO,selected.index);
                play.putExtra("position_ms",position);
                play.putExtra(PlayerActivity.EXTRA_COVER,body.optString("cover"));new Handler(Looper.getMainLooper()).post(()->{Pairing.dismiss();startActivity(play);});result.put("ok",true);
            }else if("/api/control".equals(path) && "POST".equals(method)){
                String action=body.optString("action");if(!Arrays.asList("toggle","play","pause","seek","next","previous","forward","rewind","back","episodes","comments","menu","speed","quality","speedLock","autoplay").contains(action))throw new IOException("未知操作");result=PlaybackControl.command(action,body);
            }else{json(client,404,new JSONObject().put("error","页面不存在"));return;}
            json(client,200,result);
        }catch(Exception error){try{json(client,500,new JSONObject().put("error",error.getMessage()==null?"请求失败，请重试":error.getMessage()));}catch(Exception ignored){}}
        finally{SET_COOKIE.remove();try{client.close();}catch(IOException ignored){}}
    }

    /** 手机页面打开时先问：遥控开没开、要不要密码、这台手机授权了没有、电视是不是在休息。 */
    private static JSONObject remoteState(String cookie)throws JSONException{
        JSONObject r=new JSONObject().put("on",RemoteAuth.remoteOn()).put("needPassword",RemoteAuth.hasPassword())
                .put("authorized",RemoteAuth.allowed(cookie)).put("rest",ink.pengfei.lvguo.care.RestMode.isResting());
        long end=ink.pengfei.lvguo.care.RestMode.restEndsAt();
        if(end>0)r.put("restUntil",new java.text.SimpleDateFormat("HH:mm",Locale.US).format(new java.util.Date(end)));
        return r;
    }

    /** 输入遥控密码：通过后发放长期授权（HttpOnly Cookie，不出现在 URL 里）。 */
    private static void remoteLogin(Socket socket,JSONObject body)throws IOException,JSONException{
        if(!RemoteAuth.remoteOn()){json(socket,403,new JSONObject().put("error","手机遥控已在电视上关闭").put("remoteOff",true));return;}
        if(!RemoteAuth.hasPassword()){json(socket,200,new JSONObject().put("ok",true));return;}
        RemoteAuth.Login login=RemoteAuth.login(body.optString("password"));
        if(!login.ok){
            String msg=login.locked?"密码错误次数过多，请 "+login.waitS+" 秒后再试":"密码不正确";
            json(socket,login.locked?429:403,new JSONObject().put("error",msg).put("needPassword",true));return;
        }
        SET_COOKIE.set(RemoteAuth.setCookie(login.cookie));
        try{json(socket,200,new JSONObject().put("ok",true));}finally{SET_COOKIE.remove();}
    }
    /** 本次响应要带的 Set-Cookie（授权发放 / 续期）；按线程区分。 */
    private static final ThreadLocal<String> SET_COOKIE=new ThreadLocal<>();

    private static String id(String id)throws IOException{if(id==null||!id.matches("[0-9]{5,22}"))throw new IOException("剧集编号无效");return id;}
    private static JSONObject reservationItem(Account.Entry entry,LibrarySnapshot library)throws Exception{
        JSONObject item=new JSONObject().put("id",entry.seriesId).put("title",entry.title)
            .put("cover",ink.pengfei.lvguo.img.ImageUrls.compatible(entry.cover)).put("count",entry.episodeCount)
            .put("online",entry.online).put("time",entry.time).put("publishText",entry.publishText==null?"":entry.publishText);
        return library.apply(item);
    }
    private static String seriesCover(String id,Repo.Detail detail){
        if(Store.usableCover(detail.cover))return detail.cover;
        List<Series> cached=Catalog.cached();if(cached!=null)for(Series item:cached)if(id.equals(item.seriesId)&&Store.usableCover(item.cover))return item.cover;
        String saved=Store.bestCover(id,null);if(Store.usableCover(saved))return saved;
        return detail.episodes.isEmpty()?"":detail.episodes.get(0).cover;
    }
    private static final class LibrarySnapshot {
        final Map<String,Store.Item> watched=new HashMap<>();final Set<String> favorites=new HashSet<>();final JSONObject active;
        LibrarySnapshot()throws JSONException{for(Store.Item item:Store.history())watched.put(item.seriesId,item);for(Store.Item item:Store.favorites())favorites.add(item.seriesId);active=PlaybackControl.status();}
        JSONObject apply(JSONObject item)throws JSONException{
            String id=item.optString("id");Store.Item saved=watched.get(id);
            int ep=saved==null?0:saved.lastEpisode,index=saved==null?0:saved.episodeIndex,pos=saved==null?0:saved.positionMs,dur=saved==null?0:saved.durationMs;
            if(active.optBoolean("playing") && id.equals(active.optString("id"))){ep=active.optInt("episode");pos=active.optInt("position");dur=active.optInt("duration");}
            return item.put("watched",saved!=null).put("lastEpisode",ep).put("lastIndex",index).put("position",pos).put("duration",dur).put("favorite",favorites.contains(id));
        }
    }
    private static JSONArray series(List<Series> items)throws JSONException{JSONArray out=new JSONArray();LibrarySnapshot library=new LibrarySnapshot();for(Series s:items)out.put(library.apply(new JSONObject().put("id",s.seriesId).put("title",s.title).put("cover",ink.pengfei.lvguo.img.ImageUrls.compatible(s.cover)).put("count",s.episodeCount).put("heat",s.playText())));return out;}
    private static void json(Socket socket,int code,JSONObject value)throws IOException{respond(socket,code,"application/json; charset=utf-8",value.toString().getBytes(StandardCharsets.UTF_8));}
    private static void respond(Socket socket,int code,String mime,byte[] body)throws IOException {
        OutputStream output=socket.getOutputStream();String cookie=SET_COOKIE.get();
        String header="HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+body.length+"\r\nCache-Control: "+(mime.startsWith("image/")?"public, max-age=604800":"no-store")+"\r\nX-Content-Type-Options: nosniff\r\n"+(cookie==null?"":"Set-Cookie: "+cookie+"\r\n")+"Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
    }
}
