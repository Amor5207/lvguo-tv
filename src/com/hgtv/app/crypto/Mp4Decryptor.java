package com.hgtv.app.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * CENC(cenc-aes-ctr) 解密：密文 mp4 + 16B key -> 可播 mp4。
 *
 * 关键点（都是踩过的坑）：
 *  1. 删 senc/sinf 会让 moov 变短，stco 的绝对偏移失效 → 必须用**等长 free 占位**
 *  2. stsc 表项**第二字段**才是 samples_per_chunk
 *  3. 样本条目要把 encv 改回真实编码（frma：hvc1/avc1/bvc2）、enca 改成 mp4a
 */
public final class Mp4Decryptor {

    /** Decrypt a cache file with bounded memory instead of retaining the whole episode. */
    public static void decryptFile(java.io.File file,byte[] secret) throws Exception {
        try(java.io.RandomAccessFile io=new java.io.RandomAccessFile(file,"rw")){
            long size=io.length(),at=0;RangeDecryptor decryptor=null;
            for(int n=0;n<128 && at+8<=size;n++){
                io.seek(at);long box=io.readInt()&0xffffffffL;int type=io.readInt(),header=8;
                if(box==1){box=io.readLong();header=16;}else if(box==0)box=size-at;
                if(box<header || box>size-at)throw new java.io.IOException("视频索引损坏");
                if(type==MOOV){
                    if(box>8*1024*1024)throw new java.io.IOException("视频索引过大");
                    byte[] metadata=new byte[(int)box];io.seek(at);io.readFully(metadata);
                    decryptor=new RangeDecryptor(metadata,at,secret);break;
                }
                at+=box;
            }
            if(decryptor==null)throw new java.io.IOException("缺少视频索引");
            byte[] buffer=new byte[64*1024];
            for(long pos=0;pos<size;){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                int count=(int)Math.min(buffer.length,size-pos);io.seek(pos);io.readFully(buffer,0,count);
                decryptor.apply(pos,buffer,0,count);io.seek(pos);io.write(buffer,0,count);pos+=count;
            }
        }
    }

    /** CENC sample map for random access playback; metadata stays equal length. */
    public static final class RangeDecryptor {
        private final List<Trak> tracks;
        private final SecretKeySpec key;
        private final byte[] header;
        private final long headerOffset;
        private final List<long[]> offsets = new ArrayList<>();
        public RangeDecryptor(byte[] moovBytes, long offset, byte[] secret) throws Exception {
            B moov=find(boxes(moovBytes,0,moovBytes.length),MOOV);
            if(moov==null) throw new IllegalArgumentException("缺少视频索引");
            tracks=parseTraks(moovBytes,moov);
            if(tracks.isEmpty()) throw new IllegalArgumentException("视频索引不完整");
            for(Trak tr:tracks) offsets.add(sampleOffsets(tr));
            key=new SecretKeySpec(secret,"AES");
            header=rebuildMoov(moovBytes,moov,tracks); headerOffset=offset;
            if(header.length!=moovBytes.length) throw new IllegalStateException("视频索引长度异常");
        }
        public void apply(long position,byte[] data,int start,int length) throws Exception {
            long end=position+length;
            long left=Math.max(position,headerOffset),right=Math.min(end,headerOffset+header.length);
            if(left<right) System.arraycopy(header,(int)(left-headerOffset),data,start+(int)(left-position),(int)(right-left));
            CounterBatch batch=new CounterBatch();
            for(int t=0;t<tracks.size();t++) {
                Trak tr=tracks.get(t); if(tr.ivs==null) continue;
                long[] off=offsets.get(t);
                int found=Arrays.binarySearch(off,position);
                int first=found>=0?found:Math.max(0,-found-2);
                for(int i=first;i<off.length && i<tr.ivs.length && off[i]<end;i++) {
                    if(off[i]+tr.sizes[i]<=position) continue;
                    int[] sub=tr.subs==null?null:tr.subs[i];
                    if(sub==null) batch.add(tr.ivs[i],off[i],off[i],off[i]+tr.sizes[i],position,start,length);
                    else {
                        long at=off[i];
                        for(int n=0;n+1<sub.length;n+=2) {
                            at+=sub[n];
                            batch.add(tr.ivs[i],off[i],at,at+sub[n+1],position,start,length);
                            at+=sub[n+1];
                        }
                    }
                }
            }
            batch.apply(data,key);
        }
        /** CTR counter blocks share one AES invocation, even across different sample IVs. */
        private static final class CounterBatch {
            private byte[] counters=new byte[0];
            private int[] spans=new int[0];
            private int used,count;
            void add(byte[] sampleIv,long sample,long begin,long finish,long position,int start,int length){
                long left=Math.max(begin,position),right=Math.min(finish,position+length);
                if(left>=right)return;
                long skip=left-sample,blocks=skip/16;
                int padding=(int)(skip%16),bytes=(int)(right-left),nblocks=(padding+bytes+15)/16;
                int required=used+nblocks*16;
                if(required>counters.length)counters=Arrays.copyOf(counters,Math.max(required,Math.max(4096,counters.length*2)));
                if((count+1)*3>spans.length)spans=Arrays.copyOf(spans,Math.max((count+1)*3,Math.max(48,spans.length*2)));
                spans[count*3]=start+(int)(left-position);spans[count*3+1]=used+padding;spans[count*3+2]=bytes;count++;
                byte[] iv=new byte[16];System.arraycopy(sampleIv,0,iv,0,Math.min(8,sampleIv.length));
                for(int n=15;n>=0 && blocks>0;n--){long sum=(iv[n]&255)+(blocks&255);iv[n]=(byte)sum;blocks=(blocks>>>8)+(sum>>>8);}
                for(int block=0;block<nblocks;block++){
                    System.arraycopy(iv,0,counters,used,16);used+=16;
                    for(int n=15;n>=0;n--)if(++iv[n]!=0)break;
                }
            }
            void apply(byte[] data,SecretKeySpec key)throws Exception {
                if(used==0)return;
                Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.ENCRYPT_MODE,key);
                byte[] stream=aes.doFinal(counters,0,used);
                for(int span=0;span<count;span++){
                    int dest=spans[span*3],from=spans[span*3+1],length=spans[span*3+2];
                    for(int n=0;n<length;n++)data[dest+n]^=stream[from+n];
                }
            }
        }
    }

    // ---- 常用 box tag（4 字节打包成 int）
    private static int tag(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    private static int t(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private static final int MOOV = t("moov"), TRAK = t("trak"), MDIA = t("mdia"),
            MINF = t("minf"), STBL = t("stbl"), STSD = t("stsd"), STSZ = t("stsz"),
            STCO = t("stco"), CO64 = t("co64"), STSC = t("stsc"), SENC = t("senc"),
            SAIZ = t("saiz"), SAIO = t("saio"), SGPD = t("sgpd"), SBGP = t("sbgp"),
            SINF = t("sinf"), FRMA = t("frma"), FREE = t("free"), HD  = t("hdlr"),
            EDTS = t("edts"), DINF = t("dinf"), UDTA = t("udta"), META = t("meta"),
            ILST = t("ilst"), SCHI = t("schi"), MVEX = t("mvex");

    private static final Set<Integer> CONTAINERS = new HashSet<>(Arrays.asList(
            MOOV, TRAK, MDIA, MINF, STBL, EDTS, DINF, UDTA, META, ILST, SINF, SCHI, MVEX,
            t("moof"), t("traf")));

    private static final Set<Integer> VISUAL = new HashSet<>(Arrays.asList(
            t("encv"), t("avc1"), t("avc3"), t("hvc1"), t("hev1"), t("bvc2"),
            t("av01"), t("dvh1")));
    private static final Set<Integer> AUDIO = new HashSet<>(Arrays.asList(
            t("enca"), t("mp4a"), t("ac-3"), t("ec-3")));

    static final class B {
        int hdr, payStart, payEnd, tag;
        B(int h, int p, int e, int tg) { hdr = h; payStart = p; payEnd = e; tag = tg; }
    }

    private static List<B> boxes(byte[] d, int start, int end) {
        List<B> out = new ArrayList<>();
        int i = start;
        while (i + 8 <= end) {
            long size = readU32(d, i);
            int tg = tag(d, i + 4);
            int hdr = 8;
            if (size == 1) {
                if (i + 16 > end) break;
                size = readU64(d, i + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - i;
            }
            if (size < hdr || i + size > end) break;
            out.add(new B(i, i + hdr, (int) (i + size), tg));
            i += (int) size;
        }
        return out;
    }

    private static B find(List<B> list, int tg) {
        for (B b : list) if (b.tag == tg) return b;
        return null;
    }

    private static B find(byte[] d, int start, int end, int tg) {
        return find(boxes(d, start, end), tg);
    }

    // ---------------------------------------------------------------- 轨道解析

    static final class Trak {
        int[] sizes;
        long[] chunks;
        int[] stsc;            // 每 3 个一组
        byte[][] ivs;
        int[][] subs;          // 每样本的 (clear,enc,clear,enc...) ；null 表示整样本加密
        B stbl, stsd;
    }

    private static List<Trak> parseTraks(byte[] d, B moov) {
        List<Trak> out = new ArrayList<>();
        for (B b : boxes(d, moov.payStart, moov.payEnd)) {
            if (b.tag != TRAK) continue;
            Trak tr = parseTrak(d, b);
            if (tr != null) out.add(tr);
        }
        return out;
    }

    private static Trak parseTrak(byte[] d, B trak) {
        B mdia = find(d, trak.payStart, trak.payEnd, MDIA);
        if (mdia == null) return null;
        B minf = find(d, mdia.payStart, mdia.payEnd, MINF);
        if (minf == null) return null;
        B stbl = find(d, minf.payStart, minf.payEnd, STBL);
        if (stbl == null) return null;

        Trak tr = new Trak();
        tr.stbl = stbl;
        tr.stsd = find(d, stbl.payStart, stbl.payEnd, STSD);

        B stsz = find(d, stbl.payStart, stbl.payEnd, STSZ);
        if (stsz == null) return null;
        long sampleSize = readU32(d, stsz.payStart + 4);
        int count = (int) readU32(d, stsz.payStart + 8);
        tr.sizes = new int[count];
        if (sampleSize > 0) {
            Arrays.fill(tr.sizes, (int) sampleSize);
        } else {
            for (int i = 0; i < count; i++) tr.sizes[i] = (int) readU32(d, stsz.payStart + 12 + i * 4);
        }

        B stco = find(d, stbl.payStart, stbl.payEnd, STCO);
        boolean co64 = false;
        if (stco == null) { stco = find(d, stbl.payStart, stbl.payEnd, CO64); co64 = true; }
        if (stco == null) return null;
        int nChunks = (int) readU32(d, stco.payStart + 4);
        tr.chunks = new long[nChunks];
        for (int i = 0; i < nChunks; i++) {
            tr.chunks[i] = co64 ? readU64(d, stco.payStart + 8 + i * 8)
                    : readU32(d, stco.payStart + 8 + i * 4);
        }

        B stsc = find(d, stbl.payStart, stbl.payEnd, STSC);
        if (stsc == null) return null;
        int nEnt = (int) readU32(d, stsc.payStart + 4);
        tr.stsc = new int[nEnt * 3];
        for (int i = 0; i < nEnt; i++) {
            tr.stsc[i * 3] = (int) readU32(d, stsc.payStart + 8 + i * 12);
            tr.stsc[i * 3 + 1] = (int) readU32(d, stsc.payStart + 12 + i * 12);
            tr.stsc[i * 3 + 2] = (int) readU32(d, stsc.payStart + 16 + i * 12);
        }

        B senc = find(d, stbl.payStart, stbl.payEnd, SENC);
        if (senc != null) {
            long verFlags = readU32(d, senc.payStart);
            int ivSize = (int) (verFlags & 0xFF);
            if (ivSize == 0) ivSize = 8;
            int sCnt = (int) readU32(d, senc.payStart + 4);
            tr.ivs = new byte[sCnt][];
            tr.subs = new int[sCnt][];
            int off = senc.payStart + 8;
            for (int i = 0; i < sCnt; i++) {
                tr.ivs[i] = Arrays.copyOfRange(d, off, off + ivSize);
                off += ivSize;
                if ((verFlags & 0x2) != 0) {
                    int subN = readU16(d, off);
                    off += 2;
                    int[] lst = new int[subN * 2];
                    for (int k = 0; k < subN; k++) {
                        lst[k * 2] = readU16(d, off);
                        lst[k * 2 + 1] = (int) readU32(d, off + 2);
                        off += 6;
                    }
                    tr.subs[i] = lst;
                } else {
                    tr.subs[i] = null;
                }
            }
        }
        return tr;
    }

    /** 按 stsc 展开每个样本的 (偏移, 长度) */
    private static long[] sampleOffsets(Trak tr) {
        List<Long> offs = new ArrayList<>();
        int si = 0;
        for (int ci = 0; ci < tr.chunks.length; ci++) {
            int spc = -1;
            for (int k = 0; k < tr.stsc.length / 3; k++) {
                if (ci + 1 >= tr.stsc[k * 3]) spc = tr.stsc[k * 3 + 1];
            }
            if (spc < 0) break;
            long off = tr.chunks[ci];
            for (int k = 0; k < spc; k++) {
                if (si >= tr.sizes.length) break;
                offs.add(off);
                off += tr.sizes[si];
                si++;
            }
        }
        long[] a = new long[offs.size()];
        for (int i = 0; i < a.length; i++) a[i] = offs.get(i);
        return a;
    }

    // ---------------------------------------------------------------- 解密

    public static byte[] decrypt(byte[] data, byte[] key) throws Exception {
        List<B> top = boxes(data, 0, data.length);
        B moov = find(top, MOOV);
        if (moov == null) throw new IllegalStateException("no moov");

        List<Trak> traks = parseTraks(data, moov);
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");

        for (Trak tr : traks) {
            if (tr.ivs == null) continue;
            long[] offs = sampleOffsets(tr);
            int n = Math.min(offs.length, tr.ivs.length);
            for (int k = 0; k < n; k++) {
                int off = (int) offs[k];
                int size = tr.sizes[k];
                byte[] iv = tr.ivs[k];
                int[] sub = tr.subs != null && k < tr.subs.length ? tr.subs[k] : null;
                if (sub == null) {
                    cipher.init(Cipher.DECRYPT_MODE, ks, ivSpec(iv));
                    cipher.doFinal(data, off, size, data, off);
                } else {
                    // 有 subsample：先取整样本 keystream，再只 XOR 加密段（counter 连续）
                    byte[] ksBytes = new byte[size];
                    cipher.init(Cipher.ENCRYPT_MODE, ks, ivSpec(iv));
                    cipher.doFinal(new byte[size], 0, size, ksBytes, 0);
                    int pos = 0;
                    for (int i = 0; i + 1 < sub.length; i += 2) {
                        pos += sub[i];                       // 跳过 clear
                        int enc = sub[i + 1];
                        for (int j = 0; j < enc; j++) {
                            data[off + pos + j] ^= ksBytes[pos + j];
                        }
                        pos += enc;
                    }
                }
            }
        }

        // 重建 moov（等长替换，偏移不变）
        byte[] newMoov = rebuildMoov(data, moov, traks);
        byte[] out = new byte[data.length];
        System.arraycopy(data, 0, out, 0, moov.hdr);
        System.arraycopy(newMoov, 0, out, moov.hdr, newMoov.length);
        System.arraycopy(data, moov.payEnd, out, moov.hdr + newMoov.length,
                data.length - moov.payEnd);
        return out;
    }

    /** 原地解密：moov 等长重建后回写同一块内存，峰值内存只有一个文件大小 */
    public static boolean decryptInPlace(byte[] data, byte[] key) throws Exception {
        List<B> top = boxes(data, 0, data.length);
        B moov = find(top, MOOV);
        if (moov == null) return false;

        List<Trak> traks = parseTraks(data, moov);
        SecretKeySpec ks = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");

        for (Trak tr : traks) {
            if (tr.ivs == null) continue;
            long[] offs = sampleOffsets(tr);
            int n = Math.min(offs.length, tr.ivs.length);
            for (int k = 0; k < n; k++) {
                int off = (int) offs[k];
                int size = tr.sizes[k];
                byte[] iv = tr.ivs[k];
                int[] sub = tr.subs != null && k < tr.subs.length ? tr.subs[k] : null;
                if (sub == null) {
                    cipher.init(Cipher.DECRYPT_MODE, ks, ivSpec(iv));
                    cipher.doFinal(data, off, size, data, off);
                } else {
                    byte[] ksBytes = new byte[size];
                    cipher.init(Cipher.ENCRYPT_MODE, ks, ivSpec(iv));
                    cipher.doFinal(new byte[size], 0, size, ksBytes, 0);
                    int pos = 0;
                    for (int i = 0; i + 1 < sub.length; i += 2) {
                        pos += sub[i];
                        int enc = sub[i + 1];
                        for (int j = 0; j < enc; j++) data[off + pos + j] ^= ksBytes[pos + j];
                        pos += enc;
                    }
                }
            }
        }

        byte[] newMoov = rebuildMoov(data, moov, traks);
        int oldLen = moov.payEnd - moov.hdr;
        if (newMoov.length != oldLen) return false;
        System.arraycopy(newMoov, 0, data, moov.hdr, oldLen);
        return true;
    }

    private static IvParameterSpec ivSpec(byte[] iv8) {
        byte[] c = new byte[16];
        System.arraycopy(iv8, 0, c, 0, Math.min(8, iv8.length));
        return new IvParameterSpec(c);
    }

    // ---------------------------------------------------------------- moov 重建

    private static byte[] free(int size) {
        byte[] b = new byte[size];
        writeU32(b, 0, size);
        b[4] = 'f'; b[5] = 'r'; b[6] = 'e'; b[7] = 'e';
        return b;
    }

    private static byte[] rebuild(byte[] d, int start, int end, Set<Integer> skip) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (B b : boxes(d, start, end)) {
            int size = b.payEnd - b.hdr;
            if (skip.contains(b.tag)) {
                out.write(free(size), 0, size);
                continue;
            }
            byte[] body;
            if (CONTAINERS.contains(b.tag)) body = rebuild(d, b.payStart, b.payEnd, skip);
            else body = Arrays.copyOfRange(d, b.payStart, b.payEnd);
            writeBox(out, b, body);
        }
        return out.toByteArray();
    }

    private static void writeBox(java.io.ByteArrayOutputStream out, B b, byte[] body) {
        int size = 8 + body.length;
        byte[] hdr = new byte[8];
        writeU32(hdr, 0, size);
        hdr[4] = (byte) (b.tag >> 24);
        hdr[5] = (byte) (b.tag >> 16);
        hdr[6] = (byte) (b.tag >> 8);
        hdr[7] = (byte) b.tag;
        out.write(hdr, 0, 8);
        out.write(body, 0, body.length);
    }

    private static byte[] rebuildMoov(byte[] d, B moov, List<Trak> traks) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int ti = 0;
        for (B b : boxes(d, moov.payStart, moov.payEnd)) {
            byte[] body;
            if (b.tag == TRAK && ti < traks.size()) {
                body = rebuildTrak(d, b, traks.get(ti++));
            } else {
                body = Arrays.copyOfRange(d, b.payStart, b.payEnd);
            }
            writeBox(out, b, body);
        }
        byte[] children = out.toByteArray();
        java.io.ByteArrayOutputStream moovOut = new java.io.ByteArrayOutputStream();
        writeBox(moovOut, moov, children);
        return moovOut.toByteArray();
    }

    private static byte[] rebuildTrak(byte[] d, B trak, Trak info) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (B b : boxes(d, trak.payStart, trak.payEnd)) {
            byte[] body;
            if (b.tag == MDIA) body = rebuildMdia(d, b, info);
            else body = Arrays.copyOfRange(d, b.payStart, b.payEnd);
            writeBox(out, b, body);
        }
        return out.toByteArray();
    }

    private static byte[] rebuildMdia(byte[] d, B mdia, Trak info) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (B b : boxes(d, mdia.payStart, mdia.payEnd)) {
            byte[] body;
            if (b.tag == MINF) body = rebuildMinf(d, b, info);
            else body = Arrays.copyOfRange(d, b.payStart, b.payEnd);
            writeBox(out, b, body);
        }
        return out.toByteArray();
    }

    private static byte[] rebuildMinf(byte[] d, B minf, Trak info) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (B b : boxes(d, minf.payStart, minf.payEnd)) {
            byte[] body;
            if (b.tag == STBL) body = rebuildStbl(d, b, info);
            else body = Arrays.copyOfRange(d, b.payStart, b.payEnd);
            writeBox(out, b, body);
        }
        return out.toByteArray();
    }

    private static byte[] rebuildStbl(byte[] d, B stbl, Trak info) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.Map<Integer, byte[]> rename = renameMap(d, info.stsd);
        for (B b : boxes(d, stbl.payStart, stbl.payEnd)) {
            if (b.tag == SENC || b.tag == SAIZ || b.tag == SAIO
                    || b.tag == SGPD || b.tag == SBGP) {
                byte[] f = free(b.payEnd - b.hdr);        // 等长占位
                out.write(f, 0, f.length);
                continue;
            }
            if (b.tag == STSD) {
                byte[] body = rebuildStsd(d, b, rename);
                byte[] hdr = new byte[8];
                writeU32(hdr, 0, 8 + body.length);
                hdr[4] = 's'; hdr[5] = 't'; hdr[6] = 's'; hdr[7] = 'd';   // "stsd"
                out.write(hdr, 0, 8);
                out.write(body, 0, body.length);
                continue;
            }
            byte[] body = Arrays.copyOfRange(d, b.payStart, b.payEnd);
            writeBox(out, b, body);
        }
        return out.toByteArray();
    }

    private static int firstChild(byte[] d, int ps, int pe) {
        int limit = Math.min(ps + 140, pe - 8);
        for (int o = ps; o < limit; o++) {
            long sz = readU32(d, o);
            if (sz >= 8 && sz <= pe - o) {
                boolean ok = true;
                for (int k = 4; k < 8; k++) {
                    int c = d[o + k] & 0xFF;
                    if (c < 32 || c >= 127) { ok = false; break; }
                }
                if (ok) return o;
            }
        }
        return -1;
    }

    private static byte[] rebuildStsd(byte[] d, B stsd, java.util.Map<Integer, byte[]> rename) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(d, stsd.payStart, 8);                   // version/flags + entry_count
        for (B e : boxes(d, stsd.payStart + 8, stsd.payEnd)) {
            int ps = e.payStart, pe = e.payEnd;
            int cs = firstChild(d, ps, pe);
            if (cs < 0) {
                out.write(d, e.hdr, pe - e.hdr);
                continue;
            }
            byte[] header = Arrays.copyOfRange(d, ps, cs);
            Set<Integer> skip = new HashSet<>();
            skip.add(SINF);
            byte[] body = rebuild(d, cs, pe, skip);

            byte[] type4 = rename.get(e.tag);
            int tg;
            if (type4 != null) {
                tg = ((type4[0] & 0xFF) << 24) | ((type4[1] & 0xFF) << 16)
                        | ((type4[2] & 0xFF) << 8) | (type4[3] & 0xFF);
            } else {
                tg = e.tag;
            }
            byte[] hdr = new byte[8];
            writeU32(hdr, 0, 8 + header.length + body.length);
            hdr[4] = (byte) (tg >> 24); hdr[5] = (byte) (tg >> 16);
            hdr[6] = (byte) (tg >> 8); hdr[7] = (byte) tg;
            out.write(hdr, 0, 8);
            out.write(header, 0, header.length);
            out.write(body, 0, body.length);
        }
        return out.toByteArray();
    }

    /** 读 sinf/frma，得到 encv/enca -> 真实编码四字节 */
    private static java.util.Map<Integer, byte[]> renameMap(byte[] d, B stsd) {
        java.util.Map<Integer, byte[]> m = new java.util.HashMap<>();
        if (stsd == null) return m;
        for (B e : boxes(d, stsd.payStart + 8, stsd.payEnd)) {
            B sinf = find(d, e.payStart, e.payEnd, SINF);
            if (sinf == null) {
                // 有些文件 sinf 写法不标准，退而求其次直接扫 frma
                int j = indexOf(d, e.payStart, e.payEnd, FRMA);
                if (j < 0) continue;
                if (VISUAL.contains(e.tag) || AUDIO.contains(e.tag)) {
                    m.put(e.tag, Arrays.copyOfRange(d, j + 4, j + 8));
                }
                continue;
            }
            B frma = find(d, sinf.payStart, sinf.payEnd, FRMA);
            if (frma == null) continue;
            if (VISUAL.contains(e.tag)) {
                m.put(e.tag, Arrays.copyOfRange(d, frma.payStart, frma.payStart + 4));
            } else if (AUDIO.contains(e.tag)) {
                m.put(e.tag, new byte[]{'m', 'p', '4', 'a'});
            }
        }
        return m;
    }

    private static int indexOf(byte[] d, int start, int end, int tg) {
        byte b0 = (byte) (tg >> 24), b1 = (byte) (tg >> 16),
                b2 = (byte) (tg >> 8), b3 = (byte) tg;
        for (int i = start; i + 4 <= end; i++) {
            if (d[i] == b0 && d[i + 1] == b1 && d[i + 2] == b2 && d[i + 3] == b3) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 小工具

    static long readU32(byte[] d, int o) {
        return ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    static long readU64(byte[] d, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[o + i] & 0xFF);
        return v;
    }

    static int readU16(byte[] d, int o) {
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    static void writeU32(byte[] d, int o, long v) {
        d[o] = (byte) (v >> 24); d[o + 1] = (byte) (v >> 16);
        d[o + 2] = (byte) (v >> 8); d[o + 3] = (byte) v;
    }
}
