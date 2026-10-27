package com.hgtv.app.img;
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
        try{URI uri=new URI(input);String host=uri.getHost(),path=uri.getPath();
            if(host!=null && (host.endsWith(".fqnovelpic.com") || host.endsWith(".byteimg.com")) && path!=null && path.contains("/novel-pic/")){
                String id=path.substring(path.indexOf("/novel-pic/")+11).split("[~.]")[0];
                if(id.matches("[a-fA-F0-9]{32}"))return "https://p3-novel.byteimg.com/img/novel-pic/"+id+"~tplv-noop.jpeg";
            }
        }catch(Exception ignored){}return input;
    }
}
