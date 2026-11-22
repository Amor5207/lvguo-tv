package ink.pengfei.lvguo.net;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ink.pengfei.lvguo.net.lvguosign.LvguoPlayback;

/** Authenticated, replaceable TV playback module. Never executes unsigned downloads. */
public final class TVPlaybackModule {
    private static final String PUB = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA7fR5sfuFF5AlnMUmN3X77dvkiub3GBGJFxbSmh7+CSIwUzFOQQKm/fb/5Qr7PotQU2Qq9xdKfZ9ZVtDVX24htjGOlaxgQHbwVKHHZA++xdWDVwwYLGB3lgubRGHMO2nTCn1IVCSI8mihhjQvUCxKpV+EhEmcPyp77Tjf3n1wyhKNiooZvQjN6ua4hbJACjzmUgPp2vdJxxRL4qE8S1liIwX6X5HmD2HVIYArDSXn2YCNtVuiQUN1k0IcCOkEB5Ez5RCb/3KWq1o+IdP3hVEYX8rsraPm78O3kmnlCXAGpqefH5y8ABHMR9eVrcpgQsZ28BqlQQhijRrgot7VZmonNwIDAQAB";
    private static final String MANIFEST = "https://update.pengfei.ink/api/v1/tv-playback-module";
    private static final int MAX_MODULE = 4 * 1024 * 1024, MAX_JSON = 2 * 1024 * 1024;
    private static volatile Loaded active;
    private static Context app;
    private static volatile java.util.concurrent.Future<?> startup;
    private static final class Loaded {
        final int revision; final Object instance; final Method prepare, prepareRoute, normalize;
        Loaded(int revision, Object instance, Method prepare, Method prepareRoute, Method normalize) {this.revision=revision;this.instance=instance;this.prepare=prepare;this.prepareRoute=prepareRoute;this.normalize=normalize;}
    }
    private TVPlaybackModule() {}
    public static void init(Context context) {
        app=context.getApplicationContext();
        java.util.concurrent.ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"tv-playback-module");t.setDaemon(true);return t;});
        startup=scheduler.submit(TVPlaybackModule::loadInitial);
        scheduler.scheduleWithFixedDelay(TVPlaybackModule::refresh,0,5,TimeUnit.MINUTES);
    }
    private static void loadInitial() {
        try {
            byte[] meta=asset("playback/module.json",16384), dex=asset("playback/module.dex",MAX_MODULE);
            Loaded initial=load(meta,dex); active=initial;
            try { File cache=new File(app.getFilesDir(),"tv-playback-module.json"); if(cache.length()>6L*1024*1024)throw new IOException("cached module too large");
                JSONObject stored=new JSONObject(new String(new AtomicFile(cache).readFully(),StandardCharsets.UTF_8));
                byte[] cachedMeta=stored.getString("manifest").getBytes(StandardCharsets.UTF_8), cachedDex=Base64.decode(stored.getString("dex"),Base64.DEFAULT);
                Loaded cached=load(cachedMeta,cachedDex); if(cached.revision>initial.revision)active=cached;
            } catch(Throwable ignored) { }
            // 后台预注册匿名播放设备，避免首次点播冷启动多等 1~2 秒。
            ink.pengfei.lvguo.App.io().execute(()->{ try { LvguoPlayback.moduleIdentity(); } catch(Throwable ignored) { } });
        } catch(Throwable e){Log.e("TVPlaybackModule","Could not load built-in playback module",e);}
    }
    private static byte[] asset(String path,int limit)throws Exception {try(InputStream in=app.getAssets().open(path)){return read(in,limit);}}
    private static byte[] read(InputStream in,int limit)throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("module response too large");out.write(b,0,n);}return out.toByteArray(); }
    private static byte[] download(String address,int max)throws Exception {
        URL u=new URL(address);if(!"https".equals(u.getProtocol()) || !"update.pengfei.ink".equalsIgnoreCase(u.getHost()) || u.getUserInfo()!=null || u.getPort()!=-1 || u.getRef()!=null || u.getQuery()!=null)throw new IOException("untrusted module address");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();try{c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(10000);if(c.getResponseCode()!=200)throw new IOException("module HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream()){return read(in,max);}}finally{c.disconnect();}
    }
    private static Loaded load(byte[] manifest,byte[] dex)throws Exception {
        if(manifest.length>16384 || dex.length==0 || dex.length>MAX_MODULE)throw new IOException("invalid module size");
        JSONObject m=new JSONObject(new String(manifest,StandardCharsets.UTF_8));int version=m.getInt("revision");if(version<1||m.getInt("abi")!=1)throw new IOException("incompatible module ABI");
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(dex)), url=m.getString("url");if(!sha.equals(m.getString("sha256")))throw new IOException("module hash mismatch");
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(PUB,Base64.DEFAULT)));
        Signature sig=Signature.getInstance("SHA256withRSA");sig.initVerify(key);sig.update(("lvguo-tv-playback-module-v1\n"+version+"\n"+sha+"\n"+url).getBytes(StandardCharsets.UTF_8));
        if(!sig.verify(Base64.decode(m.getString("signature"),Base64.DEFAULT)))throw new IOException("module signature invalid");
        File dir=app.getDir("playback_modules",Context.MODE_PRIVATE), file=new File(dir,"module-"+sha+".dex");
        boolean goodFile=false; if(file.isFile()) { try(FileInputStream in=new FileInputStream(file)) { goodFile=sha.equals(hex(MessageDigest.getInstance("SHA-256").digest(read(in,MAX_MODULE)))); } catch(Exception ignored) { } }
        if(!goodFile){AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=atomic.startWrite();try{stream.write(dex);atomic.finishWrite(stream);}catch(Throwable e){atomic.failWrite(stream);throw e;}}
        DexClassLoader loader=new DexClassLoader(file.getAbsolutePath(),dir.getAbsolutePath(),null,app.getClassLoader());Class<?> cls=loader.loadClass("com.hongguotv.core.PlaybackModule");Object instance=cls.getConstructor().newInstance();Method prepare=cls.getMethod("prepare",String.class,String.class),normalize=cls.getMethod("normalize",String.class,String.class);
        Method prepareRoute=null;try{prepareRoute=cls.getMethod("prepareRoute",String.class,String.class,String.class,String.class);}catch(NoSuchMethodException ignored){}
        String probe=(String)normalize.invoke(instance,"123456", "{\"code\":0,\"data\":{\"1004\":{\"123456\":{\"video_model\":{\"video_list\":[{\"main_url\":\"https://example.test/video\"}]}}}}}");if(new JSONObject(probe).getJSONObject("data").optJSONObject("123456")==null)throw new IOException("module self-test failed");
        return new Loaded(version,instance,prepare,prepareRoute,normalize);
    }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static void refresh() {
        try {
            byte[] meta=download(MANIFEST,16384);JSONObject m=new JSONObject(new String(meta,StandardCharsets.UTF_8));Loaded old=active;if(old!=null && m.getInt("revision")<=old.revision)return;
            byte[] dex=download(m.getString("url"),MAX_MODULE);Loaded next=load(meta,dex);
            JSONObject stored=new JSONObject().put("manifest",new String(meta,StandardCharsets.UTF_8)).put("dex",Base64.encodeToString(dex,Base64.NO_WRAP));
            AtomicFile file=new AtomicFile(new File(app.getFilesDir(),"tv-playback-module.json"));FileOutputStream stream=file.startWrite();try{stream.write(stored.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Throwable e){file.failWrite(stream);throw e;}
            active=next;
        } catch(Throwable e){Log.i("TVPlaybackModule","Using last verified playback module");}
    }
    /** 当前生效的签名播放模块版本；0 表示内置模块加载失败。 */
    public static int revision() { Loaded m=active; return m==null?0:m.revision; }
    private static volatile String lastGoodRoute;
    private static volatile long lastGoodRouteAt;

    public static String play(String vid) throws Exception { return play(vid, PlaybackConfig.get()); }

    public static String play(String vid, PlaybackConfig cfg) throws Exception {
        Loaded module=active;
        if(module==null && startup!=null) { try { startup.get(15,TimeUnit.SECONDS); } catch(Exception ignored) { } module=active; }
        if(module==null)throw new IOException("playback module unavailable");
        if(module.prepareRoute==null){
            return executePrepared(module,vid,(String)module.prepare.invoke(module.instance,vid,LvguoPlayback.moduleIdentity()));
        }
        java.util.List<String> base=(cfg!=null && !cfg.routes.isEmpty())?cfg.routes:java.util.Arrays.asList("dynamic","videotab","legacy");
        String drScene=(cfg!=null && cfg.drScene!=null && !cfg.drScene.isEmpty())?cfg.drScene:"default";
        java.util.List<String> order=new java.util.ArrayList<>(base.size());
        String fav=lastGoodRoute;
        if(fav!=null && System.currentTimeMillis()-lastGoodRouteAt<10*60*1000L && base.contains(fav))order.add(fav);
        for(String r:base)if(!order.contains(r))order.add(r);
        Exception lastErr=null;
        for(String route:order){
            try{
                String reqJson=(String)module.prepareRoute.invoke(module.instance,route,vid,LvguoPlayback.moduleIdentity(),drScene);
                String out=executePrepared(module,vid,reqJson);
                lastGoodRoute=route;lastGoodRouteAt=System.currentTimeMillis();
                Log.i("TVPlaybackModule","route="+route+" ok vid="+vid);
                return out;
            }catch(Exception e){
                lastErr=e;
                Log.w("TVPlaybackModule","route="+route+" failed: "+e.getMessage());
            }
        }
        throw lastErr!=null?lastErr:new IOException("all local playback routes failed");
    }

    private static String executePrepared(Loaded module,String vid,String requestJson)throws Exception {
        JSONObject request=new JSONObject(requestJson);URL u=new URL(request.getString("url"));String host=u.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equals(u.getProtocol())||!host.endsWith(".fqnovel.com")||u.getPort()!=-1||u.getUserInfo()!=null||u.getRef()!=null)throw new IOException("invalid upstream URL");
        byte[] body=Base64.decode(request.optString("body",""),Base64.DEFAULT);if(body.length>65536)throw new IOException("playback request too large");
        String method=request.optString("method",body.length>0?"POST":"GET");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();try{c.setRequestMethod(method);c.setDoOutput(body.length>0);c.setInstanceFollowRedirects(false);c.setConnectTimeout(8000);c.setReadTimeout(12000);JSONObject headers=request.getJSONObject("headers");java.util.Iterator<String> names=headers.keys();while(names.hasNext()){String key=names.next();if(!key.equalsIgnoreCase("cookie")&&!key.equalsIgnoreCase("host")&&!key.equalsIgnoreCase("content-length"))c.setRequestProperty(key,headers.getString(key));}if(body.length>0){try(OutputStream out=c.getOutputStream()){out.write(body);}}if(c.getResponseCode()!=200)throw new IOException("playback HTTP "+c.getResponseCode());String response;try(InputStream in=c.getInputStream()){response=new String(read(in,MAX_JSON),StandardCharsets.UTF_8);}if(response.trim().isEmpty()){LvguoPlayback.rotateModuleIdentity();throw new IOException("empty playback response");}return (String)module.normalize.invoke(module.instance,vid,response);}finally{c.disconnect();}
    }
}
