package com.hgtv.app.media;

import android.media.MediaDataSource;
import com.hgtv.app.App;
import com.hgtv.app.crypto.Mp4Decryptor;
import com.hgtv.app.crypto.Spade;
import com.hgtv.app.model.Gear;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP range reads with bounded block cache and per-sample CENC decryption. */
public final class StreamingSource extends MediaDataSource {
    public interface Progress {void received(long bytes);}
    private final Progress progress;
    private static final int BLOCK=256*1024;
    private static final int AHEAD_BLOCKS=3;
    private final Gear gear;
    private volatile long size;
    private volatile boolean closed;
    private volatile boolean preferBackup;
    private final AtomicLong networkNanos=new AtomicLong(),cryptoNanos=new AtomicLong(),readNanos=new AtomicLong(),fetchCount=new AtomicLong(),receivedBytes=new AtomicLong();
    private final Set<HttpURLConnection> active=Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection,Boolean>());
    private final ConcurrentHashMap<Long,FutureTask<byte[]>> requests=new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor AHEAD=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(6),r->{Thread t=new Thread(r,"VideoReadAhead");t.setPriority(Thread.MIN_PRIORITY);t.setDaemon(true);return t;});
    private static final ThreadPoolExecutor CLOSER=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(32),r->{Thread t=new Thread(r,"VideoConnectionClose");t.setDaemon(true);return t;});
    private Mp4Decryptor.RangeDecryptor decryptor;
    private byte[] timingHeader;
    private long timingOffset;
    private final LinkedHashMap<Long,byte[]> blocks=new LinkedHashMap<Long,byte[]>(20,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<Long,byte[]> entry){return size()>16;}
    };
    public StreamingSource(Gear g) throws Exception {this(g,null);}
    public StreamingSource(Gear g,Progress listener) throws Exception {
        progress=listener;
        gear=g; size=g.size;
        try {
        block(0);
        if(size<=0) throw new IOException("视频长度不可用");
        if(g.encrypted || android.os.Build.VERSION.SDK_INT<=28) {
            byte[] secret=g.encrypted?Spade.spadeToKey(g.spadeA):null;
            if(g.encrypted && secret==null) throw new IOException("视频密钥不可用");
            long at=0; boolean found=false;
            for(int n=0;n<64 && at+8<=size;n++) {
                byte[] h=new byte[16]; int count=readRaw(at,h,0,(int)Math.min(16,size-at));
                if(count<8)break;
                long box=u32(h,0); int header=8;
                if(box==1){box=(u32(h,8)<<32)|u32(h,12);header=16;}
                if(box==0)box=size-at;
                if(box<header || at+box>size)throw new IOException("视频索引损坏");
                if(h[4]=='m' && h[5]=='o' && h[6]=='o' && h[7]=='v') {
                    if(box>8*1024*1024)throw new IOException("视频索引过大");
                    byte[] metadata=new byte[(int)box];
                    if(readRaw(at,metadata,0,metadata.length)!=metadata.length)throw new EOFException();
                    if(android.os.Build.VERSION.SDK_INT<=28 && Mp4Timing.normalize(metadata)){
                        timingHeader=metadata;timingOffset=at;
                        android.util.Log.i("TVPlayback","Normalized legacy MP4 movie clock");
                    }
                    if(g.encrypted)decryptor=new Mp4Decryptor.RangeDecryptor(metadata,at,secret);
                    found=true;break;
                }
                at+=box;
            }
            if(!found)throw new IOException("未找到视频索引");
            // Constructor reads raw metadata. Convert its retained blocks once before publishing.
            for(Map.Entry<Long,byte[]> entry:blocks.entrySet())decrypt(entry.getKey(),entry.getValue());
        }
        }catch(Exception error){close();throw error;}
    }
    private static long u32(byte[] b,int at){return ((b[at]&255L)<<24)|((b[at+1]&255L)<<16)|((b[at+2]&255L)<<8)|(b[at+3]&255L);}
    private byte[] block(long number) throws IOException {
        checkOpen();
        synchronized(blocks){byte[] hit=blocks.get(number);if(hit!=null)return hit;}
        FutureTask<byte[]> created=new FutureTask<>(()->loadBlock(number));
        FutureTask<byte[]> previous=requests.putIfAbsent(number,created);
        FutureTask<byte[]> task=previous==null?created:previous;
        try {
            // A foreground read can claim queued work instead of waiting behind another source.
            task.run();return task.get();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
        catch(CancellationException e){throw new IOException("视频已关闭",e);}
        catch(ExecutionException e){throw new IOException("视频分段读取失败",e.getCause());}
        finally{requests.remove(number,task);}
    }
    private void checkOpen()throws IOException {
        if(closed || Thread.currentThread().isInterrupted())throw new InterruptedIOException("视频读取已取消");
    }
    private void decrypt(long number,byte[] data)throws IOException {
        if(decryptor!=null){long start=System.nanoTime();try{decryptor.apply(number*BLOCK,data,0,data.length);}
        catch(Exception e){throw new IOException("视频解密失败",e);}
        finally{cryptoNanos.addAndGet(System.nanoTime()-start);}}
        // Encrypted sources already include the normalized clock in their rebuilt header.
        if(decryptor==null && timingHeader!=null){
            long pos=number*BLOCK,left=Math.max(pos,timingOffset),right=Math.min(pos+data.length,timingOffset+timingHeader.length);
            if(left<right)System.arraycopy(timingHeader,(int)(left-timingOffset),data,(int)(left-pos),(int)(right-left));
        }
    }
    private byte[] loadBlock(long number)throws IOException {
        synchronized(blocks){byte[] hit=blocks.get(number);if(hit!=null)return hit;}
        IOException error=null;
        String[] urls=preferBackup?new String[]{gear.backupUrl,gear.mainUrl}:new String[]{gear.mainUrl,gear.backupUrl};
        for(String url:urls) {
            checkOpen();
            if(url==null||url.isEmpty())continue;
            try {
                byte[] data;long start=System.nanoTime();fetchCount.incrementAndGet();
                try{data=fetch(url,number*BLOCK);}finally{networkNanos.addAndGet(System.nanoTime()-start);}
                checkOpen();decrypt(number,data);
                synchronized(blocks){checkOpen();blocks.put(number,data);}
                preferBackup=url.equals(gear.backupUrl) && !url.equals(gear.mainUrl);
                return data;
            } catch(IOException e){error=e;}
        }
        throw error==null?new IOException("视频地址为空"):error;
    }
    private byte[] fetch(String url,long begin) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        active.add(c);
        c.setConnectTimeout(6000);c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent",App.UA);
        long end=begin+BLOCK-1;if(size>0)end=Math.min(end,size-1);
        c.setRequestProperty("Range","bytes="+begin+"-"+end);
        try {
            checkOpen();
            int status=c.getResponseCode();
            if(status!=206 && status!=200)throw new IOException("视频 HTTP "+status);
            String range=c.getHeaderField("Content-Range");
            if(range!=null && range.contains("/")) {
                try{size=Long.parseLong(range.substring(range.lastIndexOf('/')+1));}catch(NumberFormatException ignored){}
            }else if(status==200 && c.getContentLength()>0)size=c.getContentLength();
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream(BLOCK)) {
                if(status==200 && begin>0) {
                    long skipped=0;
                    while(skipped<begin){checkOpen();long n=in.skip(begin-skipped);if(n<=0){if(in.read()<0)throw new EOFException();n=1;}skipped+=n;}
                }
                byte[] buffer=new byte[32768];int remaining=(int)(end-begin+1);
                while(remaining>0){checkOpen();int n=in.read(buffer,0,Math.min(buffer.length,remaining));if(n<0)break;long total=receivedBytes.addAndGet(n);if(progress!=null)progress.received(total);out.write(buffer,0,n);remaining-=n;}
                if(out.size()==0)throw new EOFException("视频内容为空");
                if(size>0 && out.size()<Math.min(BLOCK,size-begin))throw new EOFException("视频分段不完整");
                return out.toByteArray();
            }
        }finally{c.disconnect();active.remove(c);}
    }
    private int readRaw(long pos,byte[] buffer,int offset,int count)throws IOException {
        int done=0;
        while(done<count && pos+done<size) {
            long at=pos+done;byte[] block=block(at/BLOCK);int inside=(int)(at%BLOCK);
            int n=Math.min(count-done,block.length-inside);if(n<=0)break;
            System.arraycopy(block,inside,buffer,offset+done,n);done+=n;
        }
        return done;
    }
    @Override public synchronized int readAt(long position,byte[] buffer,int offset,int size)throws IOException {
        checkOpen();
        if(position<0)throw new IOException("无效读取位置");
        if(size==0)return 0;
        if(position>=this.size)return -1;
        long start=System.nanoTime();int count;
        try{count=readRaw(position,buffer,offset,size);}finally{readNanos.addAndGet(System.nanoTime()-start);}
        if(count==0)return -1;
        readAhead((position+count-1)/BLOCK+1);
        return count;
    }
    private void readAhead(long number) {
        if(closed)return;
        for(long at=number;at<number+AHEAD_BLOCKS && at*BLOCK<size;at++){
            final long next=at;
            synchronized(blocks){if(blocks.containsKey(next))continue;}
            FutureTask<byte[]> task=new FutureTask<>(()->loadBlock(next));
            if(requests.putIfAbsent(next,task)!=null)continue;
            try{AHEAD.execute(()->{try{task.run();}finally{requests.remove(next,task);}});}
            catch(RejectedExecutionException ignored){requests.remove(next,task);}
        }
    }
    /** Keep about 1 MiB of the next opening plus its index within the bounded cache. */
    public void warmStart()throws IOException {for(int number=1;number<4 && (long)number*BLOCK<size;number++)block(number);}
    public String stats(){return "fetches="+fetchCount.get()+" networkMs="+networkNanos.get()/1000000+" cryptoMs="+cryptoNanos.get()/1000000+" readMs="+readNanos.get()/1000000;}
    public long receivedBytes(){return receivedBytes.get();}
    @Override public long getSize(){return size;}
    @Override public void close(){
        closed=true;
        for(FutureTask<byte[]> task:requests.values())task.cancel(true);
        requests.clear();synchronized(blocks){blocks.clear();}
        // Android HTTPS disconnect can write TLS shutdown bytes. Never do it on the UI thread.
        for(HttpURLConnection c:active)try{CLOSER.execute(()->{try{c.disconnect();}catch(RuntimeException ignored){}});}
        catch(RejectedExecutionException ignored){/* The bounded read timeout still releases this connection. */}
    }
}
