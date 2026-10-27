package com.hgtv.app.media;

import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** Equal-length movie-clock normalization for legacy Android MP4 extractors. */
public final class Mp4Timing {
    private Mp4Timing() {}
    private static long u32(byte[] d,int p){return ((d[p]&255L)<<24)|((d[p+1]&255L)<<16)|((d[p+2]&255L)<<8)|(d[p+3]&255L);}
    private static long value(byte[] d,int p,int width){long n=0;for(int i=0;i<width;i++)n=(n<<8)|(d[p+i]&255L);return n;}
    private static void put(byte[] d,int p,int width,long n){for(int i=width-1;i>=0;i--){d[p+i]=(byte)n;n>>>=8;}}
    private static final class Box {int p,end;String type;Box(int p,int end,String type){this.p=p;this.end=end;this.type=type;}}
    private static List<Box> boxes(byte[] d,int p,int end)throws IOException {
        List<Box> out=new ArrayList<>();
        while(p<end){if(p+8>end)throw new IOException("Incomplete MP4 box");long n=u32(d,p);if(n<8||n>end-p)throw new IOException("Unsupported MP4 box");out.add(new Box(p+8,p+(int)n,new String(d,p+4,4,"US-ASCII")));p+=(int)n;}
        return out;
    }
    private static Box child(byte[] d,Box b,String type)throws IOException{for(Box c:boxes(d,b.p,b.end))if(type.equals(c.type))return c;return null;}
    private static void require(Box b,int p,int width)throws IOException{if(p<b.p||p+width>b.end)throw new IOException("Incomplete MP4 timing");}
    private static void scale(byte[] d,Box b,int p,int width,long from,long to)throws IOException {
        require(b,p,width);long n=value(d,p,width);
        if(n==-1 || (width==4 && n==0xffffffffL))return;
        if(n<0)throw new IOException("Unsupported MP4 duration");
        BigInteger result=BigInteger.valueOf(n).multiply(BigInteger.valueOf(to)).add(BigInteger.valueOf(from/2)).divide(BigInteger.valueOf(from));
        if(result.bitLength()>(width==4?32:63))throw new IOException("MP4 duration overflow");put(d,p,width,result.longValue());
    }
    /** Change movie units only; track timestamps, samples and edit media_time stay intact.
     * Android 9 incorrectly divides audio edit media_time by the movie timescale.
     * Using the audio track's clock fixes that without changing standard MP4 timing.
     */
    public static boolean normalize(byte[] metadata) {
        try {
            byte[] d=metadata.clone();List<Box> top=boxes(d,0,d.length);if(top.size()!=1||!"moov".equals(top.get(0).type))return false;
            Box moov=top.get(0),movie=child(d,moov,"mvhd");if(movie==null)return false;
            int mvVersion=d[movie.p]&255;if(mvVersion>1)return false;
            int clock=movie.p+(mvVersion==1?20:12);require(movie,clock,4);long from=u32(d,clock),to=0;
            for(Box track:boxes(d,moov.p,moov.end))if("trak".equals(track.type)){
                Box mdia=child(d,track,"mdia");if(mdia==null)continue;Box h=child(d,mdia,"hdlr"),m=child(d,mdia,"mdhd");if(h==null||m==null)continue;
                require(h,h.p+8,4);if(!"soun".equals(new String(d,h.p+8,4,"US-ASCII")))continue;
                int ver=d[m.p]&255;if(ver>1)return false;int pos=m.p+(ver==1?20:12);require(m,pos,4);long rate=u32(d,pos);
                if(to!=0&&to!=rate)return false;to=rate;
            }
            if(from==0||to==0||from==to)return false;
            scale(d,movie,clock+4,mvVersion==1?8:4,from,to);put(d,clock,4,to);
            for(Box track:boxes(d,moov.p,moov.end))if("trak".equals(track.type)){
                Box tk=child(d,track,"tkhd");if(tk!=null){int ver=d[tk.p]&255;if(ver>1)return false;scale(d,tk,tk.p+(ver==1?28:20),ver==1?8:4,from,to);}
                Box edits=child(d,track,"edts"),el=edits==null?null:child(d,edits,"elst");if(el==null)continue;
                int ver=d[el.p]&255;if(ver>1)return false;require(el,el.p,8);long count=u32(d,el.p+4);int step=ver==1?20:12;
                if(count>(el.end-el.p-8)/step)return false;
                for(int i=0;i<count;i++)scale(d,el,el.p+8+i*step,ver==1?8:4,from,to);
            }
            System.arraycopy(d,0,metadata,0,d.length);return true;
        }catch(IOException|RuntimeException ignored){return false;}
    }
    public static void normalizeFile(File file)throws IOException {
        try(RandomAccessFile io=new RandomAccessFile(file,"rw")){
            for(long at=0;at+8<=io.length();){io.seek(at);long size=io.readInt()&0xffffffffL;int type=io.readInt();int head=8;if(size==1){size=io.readLong();head=16;}if(size==0)size=io.length()-at;
                if(size<head||size>io.length()-at)return;
                if(type==0x6d6f6f76){if(size>8*1024*1024)return;byte[] data=new byte[(int)size];io.seek(at);io.readFully(data);if(normalize(data)){io.seek(at);io.write(data);}return;}at+=size;}
        }
    }
}
