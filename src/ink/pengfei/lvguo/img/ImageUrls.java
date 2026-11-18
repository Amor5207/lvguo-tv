package ink.pengfei.lvguo.img;
import java.net.URI;
/** Public JPEG rendition for legacy TV decoders and mobile browsers. */
public final class ImageUrls {
    private ImageUrls(){}
    public static String preview(String input){
        String url=compatible(input);if(url==null)return null;
        if(url.startsWith("https://p3-novel.byteimg.com/img/novel-pic/"))return url.split("~")[0]+"~tplv-shrink:48:0.jpeg";
        return null;
    }
    public static String compatible(String input){
        if(input==null)return null;
        // 账号头像/背景等签名图常给 http://，Android 9+ 默认禁止明文请求 → 统一升级 https（CDN 都支持）
        if(input.startsWith("http://")){
            try{String h=new URI(input).getHost();
                if(h!=null&&(h.endsWith(".fqnovelpic.com")||h.endsWith(".byteimg.com")||h.endsWith(".snssdk.com")||h.endsWith(".pstatp.com")))input="https://"+input.substring(7);
            }catch(Exception ignored){}
        }
        try{URI uri=new URI(input);String host=uri.getHost(),path=uri.getPath();
            if(host!=null && (host.endsWith(".fqnovelpic.com") || host.endsWith(".byteimg.com")) && path!=null){
                // 封面在 /novel-pic/，头像在 /novel-static/；都统一成公开 JPEG 版式，
                // 避免 ~tplv-obj.image 这种默认（WebP/HEIC）在老解码器上出不来。
                for(String seg:new String[]{"/novel-pic/","/novel-static/"}){
                    if(path.contains(seg)){
                        String id=path.substring(path.indexOf(seg)+seg.length()).split("[~.]")[0];
                        if(id.matches("[a-fA-F0-9]{32}"))return "https://p3-novel.byteimg.com/img"+seg+id+"~tplv-noop.jpeg";
                    }
                }
            }
        }catch(Exception ignored){}return input;
    }
}
