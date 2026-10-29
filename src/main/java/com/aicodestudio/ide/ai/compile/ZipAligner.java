package com.aicodestudio.ide.ai.compile;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * 设备端 zipalign：重写 zip，使每个条目的 data 起点 4 字节对齐。
 *
 * v15.2：改为流式 RandomAccessFile，不再 Files.readAllBytes 整个 APK，
 * 避免 200MB+ APK 在手机上 OOM。central directory 很小，只读那一段；
 * 条目数据用 FileChannel.transferTo 零拷贝流式写。
 */
public class ZipAligner {
    static final int ALIGN = 4;
    static class Entry {
        long localOff; long dataOff; long dataEnd;
        int method, flag, crc, csize, usize, nlen, xlen, clen, verNeed, modTime, modDate, extAttr;
        byte[] name;
        int localXlen; byte[] localExtra;
        byte[] cdExtra; byte[] cdComment;
    }

    /** 用 RandomAccessFile 读 central directory（不需要读整个文件） */
    static List<Entry> parseCentralDir(RandomAccessFile raf) throws IOException {
        long n = raf.length();
        if (n < 22) throw new IOException("file too small");
        // 从末尾找 EOCD（最后 64KB 足够覆盖 comment）
        int eocd = -1;
        int scanLen = (int) Math.min(n, 65557);
        byte[] tail = new byte[scanLen];
        raf.seek(n - scanLen);
        raf.readFully(tail);
        int tailOff = scanLen;
        for (int i = scanLen - 22; i >= 0; i--) {
            if (sig(tail, i, 0x06054b50)) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("no EOCD");
        int cdSize = le32(tail, eocd + 12);
        long cdOff = le32(tail, eocd + 16);
        int count = le16(tail, eocd + 10);

        // 读整个 central directory（通常很小，几KB~几十KB）
        byte[] cd = new byte[cdSize];
        raf.seek(cdOff);
        raf.readFully(cd);

        List<Entry> es = new ArrayList<>();
        int p = 0;
        for (int k = 0; k < count; k++) {
            if (!sig(cd, p, 0x02014b50)) throw new IOException("bad CD sig @" + p);
            Entry e = new Entry();
            e.verNeed = le16(cd, p + 6); e.flag = le16(cd, p + 8); e.method = le16(cd, p + 10);
            e.modTime = le16(cd, p + 12); e.modDate = le16(cd, p + 14);
            e.crc = le32(cd, p + 16); e.csize = le32(cd, p + 20); e.usize = le32(cd, p + 24);
            e.nlen = le16(cd, p + 28); e.xlen = le16(cd, p + 30); e.clen = le16(cd, p + 32);
            e.extAttr = le32(cd, p + 38);
            e.localOff = le32(cd, p + 42) & 0xffffffffL;
            e.name = Arrays.copyOfRange(cd, p + 46, p + 46 + e.nlen);
            e.cdExtra = Arrays.copyOfRange(cd, p + 46 + e.nlen, p + 46 + e.nlen + e.xlen);
            e.cdComment = Arrays.copyOfRange(cd, p + 46 + e.nlen + e.xlen, p + 46 + e.nlen + e.xlen + e.clen);
            es.add(e);
            p += 46 + e.nlen + e.xlen + e.clen;
        }
        es.sort(Comparator.comparingLong(a -> a.localOff));
        // 读每个 local header 的 extra（小数据）
        for (Entry e : es) {
            raf.seek(e.localOff + 26);
            byte[] lh = new byte[2];
            raf.readFully(lh);
            int nlen2 = le16(lh, 0);
            raf.readFully(lh);
            int xlen2 = le16(lh, 0);
            e.localXlen = xlen2;
            if (xlen2 > 0) {
                e.localExtra = new byte[xlen2];
                raf.readFully(e.localExtra);
            } else {
                e.localExtra = new byte[0];
            }
            e.dataOff = e.localOff + 30 + nlen2 + xlen2;
            e.dataEnd = e.dataOff + (e.csize & 0xffffffffL);
        }
        return es;
    }

    static long writeLocalEntry(FileChannel out, Entry e, long outPos) throws IOException {
        long dataStart = outPos + 30 + e.nlen + e.localXlen;
        int pad = (int) ((ALIGN - (dataStart % ALIGN)) % ALIGN);
        int newFlag = e.flag & ~8;
        ByteBuffer lh = ByteBuffer.allocate(30 + e.nlen + e.localXlen + pad);
        lh.order(ByteOrder.LITTLE_ENDIAN);
        lh.putInt(0x04034b50);
        lh.putShort((short) e.verNeed);
        lh.putShort((short) newFlag);
        lh.putShort((short) e.method);
        lh.putShort((short) e.modTime);
        lh.putShort((short) e.modDate);
        lh.putInt(e.crc);
        lh.putInt(e.csize);
        lh.putInt(e.usize);
        lh.putShort((short) e.nlen);
        lh.putShort((short) (e.localXlen + pad));
        lh.put(e.name);
        lh.put(e.localExtra);
        lh.flip();
        out.write(lh);
        return outPos;
    }

    /** 仅重对齐：流式，不把整个 APK 读进内存 */
    public static void realign(File in, File out) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(in, "r");
             FileChannel inCh = new FileInputStream(in).getChannel();
             FileChannel outCh = new FileOutputStream(out).getChannel()) {
            List<Entry> es = parseCentralDir(raf);
            int count = es.size();
            int origCdSize = 0;
            for (Entry e : es) origCdSize += 46 + e.nlen + e.xlen + e.clen;

            List<Long> newOffsets = new ArrayList<>();
            long outPos = 0;
            for (Entry e : es) {
                long off = writeLocalEntry(outCh, e, outPos);
                newOffsets.add(off);
                long dataLen = e.dataEnd - e.dataOff;
                // 流式拷贝条目数据，不经过大 byte[]
                long transferred = 0;
                while (transferred < dataLen) {
                    long n = inCh.transferTo(e.dataOff + transferred,
                            Math.min(dataLen - transferred, 1 << 20), outCh);
                    if (n <= 0) break;
                    transferred += n;
                }
                int pad = (int)((ALIGN - ((outPos + 30 + e.nlen + e.localXlen) % ALIGN)) % ALIGN);
                outPos = off + 30 + e.nlen + e.localXlen + pad + dataLen;
            }
            // central directory
            long newCdOff = outPos;
            for (int k = 0; k < count; k++) {
                Entry e = es.get(k);
                int recLen = 46 + e.nlen + e.xlen + e.clen;
                ByteBuffer ce = ByteBuffer.allocate(recLen);
                ce.order(ByteOrder.LITTLE_ENDIAN);
                ce.putInt(0x02014b50); ce.putShort((short)20); ce.putShort((short)e.verNeed);
                ce.putShort((short)(e.flag & ~8)); ce.putShort((short)e.method);
                ce.putShort((short)e.modTime); ce.putShort((short)e.modDate);
                ce.putInt(e.crc); ce.putInt(e.csize); ce.putInt(e.usize);
                ce.putShort((short)e.nlen); ce.putShort((short)e.xlen); ce.putShort((short)e.clen);
                ce.putShort((short)0); ce.putShort((short)0); ce.putInt(e.extAttr);
                ce.putInt((int)(long)newOffsets.get(k));
                ce.put(e.name);
                ce.put(e.cdExtra);
                ce.put(e.cdComment);
                ce.flip();
                outCh.write(ce);
            }
            ByteBuffer eo = ByteBuffer.allocate(22);
            eo.order(ByteOrder.LITTLE_ENDIAN);
            eo.putInt(0x06054b50); eo.putShort((short)0); eo.putShort((short)0);
            eo.putShort((short)count); eo.putShort((short)count);
            eo.putInt(origCdSize); eo.putInt((int)newCdOff); eo.putShort((short)0);
            eo.flip();
            outCh.write(eo);
        }
    }

    private static boolean sig(byte[] b, int o, int s) {
        return (b[o]&0xff)==(s&0xff) && (b[o+1]&0xff)==((s>>8)&0xff) && (b[o+2]&0xff)==((s>>16)&0xff) && (b[o+3]&0xff)==((s>>24)&0xff);
    }
    private static int le16(byte[] b, int o) { return (b[o]&0xff) | ((b[o+1]&0xff)<<8); }
    private static int le32(byte[] b, int o) { return (b[o]&0xff) | ((b[o+1]&0xff)<<8) | ((b[o+2]&0xff)<<16) | ((b[o+3]&0xff)<<24); }
}
