package com.aicodestudio.ide.ai.compile;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * 设备端 zipalign：重写 zip，使每个条目的 data 起点 4 字节对齐。
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

    static List<Entry> parseCentralDir(byte[] b) throws IOException {
        int n = b.length;
        int eocd = -1;
        for (int i = n - 22; i >= 0; i--) {
            if (sig(b, i, 0x06054b50)) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("no EOCD");
        int cdSize = le32(b, eocd + 12), cdOff = le32(b, eocd + 16), count = le16(b, eocd + 10);
        List<Entry> es = new ArrayList<>();
        int p = cdOff;
        for (int k = 0; k < count; k++) {
            if (!sig(b, p, 0x02014b50)) throw new IOException("bad CD sig @" + p);
            Entry e = new Entry();
            e.verNeed = le16(b, p + 6); e.flag = le16(b, p + 8); e.method = le16(b, p + 10);
            e.modTime = le16(b, p + 12); e.modDate = le16(b, p + 14);
            e.crc = le32(b, p + 16); e.csize = le32(b, p + 20); e.usize = le32(b, p + 24);
            e.nlen = le16(b, p + 28); e.xlen = le16(b, p + 30); e.clen = le16(b, p + 32);
            e.extAttr = le32(b, p + 38);
            e.localOff = le32(b, p + 42) & 0xffffffffL;
            e.name = Arrays.copyOfRange(b, p + 46, p + 46 + e.nlen);
            e.cdExtra = Arrays.copyOfRange(b, p + 46 + e.nlen, p + 46 + e.nlen + e.xlen);
            e.cdComment = Arrays.copyOfRange(b, p + 46 + e.nlen + e.xlen, p + 46 + e.nlen + e.xlen + e.clen);
            es.add(e);
            p += 46 + e.nlen + e.xlen + e.clen;
        }
        es.sort(Comparator.comparingLong(a -> a.localOff));
        for (Entry e : es) {
            int lo = (int) e.localOff;
            if (!sig(b, lo, 0x04034b50)) throw new IOException("bad local sig @" + lo);
            int nlen2 = le16(b, lo + 26);
            int xlen2 = le16(b, lo + 28);
            e.localXlen = xlen2;
            e.localExtra = Arrays.copyOfRange(b, lo + 30 + nlen2, lo + 30 + nlen2 + xlen2);
            e.dataOff = e.localOff + 30 + nlen2 + xlen2;
            e.dataEnd = e.dataOff + e.csize;
        }
        return es;
    }

    static long writeLocalEntry(FileOutputStream os, Entry e, long outPos) throws IOException {
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
        os.write(lh.array());
        return outPos;
    }

    public static void realignAndAppend(File base, File add, File out, String entryName) throws Exception {
        byte[] b = Files.readAllBytes(base.toPath());
        List<Entry> es = parseCentralDir(b);
        int count = es.size();

        byte[] addBytes = Files.readAllBytes(add.toPath());
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        def.setInput(addBytes); def.finish();
        ByteArrayOutputStream cbo = new ByteArrayOutputStream();
        byte[] tbuf = new byte[8192];
        while (!def.finished()) { int l = def.deflate(tbuf); cbo.write(tbuf, 0, l); }
        def.end();
        byte[] comp = cbo.toByteArray();
        CRC32 crc32 = new CRC32(); crc32.update(addBytes); int addCrc = (int) crc32.getValue();
        byte[] addName = entryName.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream os = new FileOutputStream(out)) {
            List<Integer> newOffsets = new ArrayList<>();
            long outPos = 0;
            for (Entry e : es) {
                int off = (int) writeLocalEntry(os, e, outPos);
                newOffsets.add(off);
                int dataLen = (int)(e.dataEnd - e.dataOff);
                os.write(b, (int) e.dataOff, dataLen);
                outPos = off + 30 + e.nlen + e.localXlen + ((ALIGN - ((outPos + 30 + e.nlen + e.localXlen) % ALIGN)) % ALIGN) + dataLen;
            }
            long dataStart = outPos + 30 + addName.length;
            int pad = (int) ((ALIGN - (dataStart % ALIGN)) % ALIGN);
            ByteBuffer lh = ByteBuffer.allocate(30 + addName.length + pad);
            lh.order(ByteOrder.LITTLE_ENDIAN);
            lh.putInt(0x04034b50); lh.putShort((short)20); lh.putShort((short)0);
            lh.putShort((short)8); lh.putShort((short)0); lh.putShort((short)0);
            lh.putInt(addCrc); lh.putInt(comp.length); lh.putInt(addBytes.length);
            lh.putShort((short)addName.length); lh.putShort((short)pad);
            lh.put(addName);
            os.write(lh.array());
            int newEntryOff = (int) outPos;
            os.write(comp);
            outPos = outPos + 30 + addName.length + pad + comp.length;
            int newCdOff = (int) outPos;
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
                ce.putInt(newOffsets.get(k));
                ce.put(e.name);
                ce.put(e.cdExtra);
                ce.put(e.cdComment);
                os.write(ce.array());
            }
            ByteBuffer ce = ByteBuffer.allocate(46 + addName.length);
            ce.order(ByteOrder.LITTLE_ENDIAN);
            ce.putInt(0x02014b50); ce.putShort((short)20); ce.putShort((short)20);
            ce.putShort((short)0); ce.putShort((short)8); ce.putShort((short)0); ce.putShort((short)0);
            ce.putInt(addCrc); ce.putInt(comp.length); ce.putInt(addBytes.length);
            ce.putShort((short)addName.length); ce.putShort((short)0); ce.putShort((short)0);
            ce.putShort((short)0); ce.putShort((short)0); ce.putInt(0); ce.putInt(newEntryOff);
            ce.put(addName);
            os.write(ce.array());
            int newCdSize = 0;
            for (Entry e : es) newCdSize += 46 + e.nlen + e.xlen + e.clen;
            newCdSize += 46 + addName.length;
            ByteBuffer eo = ByteBuffer.allocate(22);
            eo.order(ByteOrder.LITTLE_ENDIAN);
            eo.putInt(0x06054b50); eo.putShort((short)0); eo.putShort((short)0);
            eo.putShort((short)(count+1)); eo.putShort((short)(count+1));
            eo.putInt(newCdSize); eo.putInt(newCdOff); eo.putShort((short)0);
            os.write(eo.array());
        }
    }

    public static void realign(File in, File out) throws Exception {
        byte[] b = Files.readAllBytes(in.toPath());
        List<Entry> es = parseCentralDir(b);
        int count = es.size();
        int origCdSize = 0;
        for (Entry e : es) origCdSize += 46 + e.nlen + e.xlen + e.clen;
        try (FileOutputStream os = new FileOutputStream(out)) {
            List<Integer> newOffsets = new ArrayList<>();
            long outPos = 0;
            for (Entry e : es) {
                int off = (int) writeLocalEntry(os, e, outPos);
                newOffsets.add(off);
                int dataLen = (int)(e.dataEnd - e.dataOff);
                os.write(b, (int) e.dataOff, dataLen);
                int pad = (int)((ALIGN - ((outPos + 30 + e.nlen + e.localXlen) % ALIGN)) % ALIGN);
                outPos = off + 30 + e.nlen + e.localXlen + pad + dataLen;
            }
            int newCdOff = (int) outPos;
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
                ce.putInt(newOffsets.get(k));
                ce.put(e.name);
                ce.put(e.cdExtra);
                ce.put(e.cdComment);
                os.write(ce.array());
            }
            ByteBuffer eo = ByteBuffer.allocate(22);
            eo.order(ByteOrder.LITTLE_ENDIAN);
            eo.putInt(0x06054b50); eo.putShort((short)0); eo.putShort((short)0);
            eo.putShort((short)count); eo.putShort((short)count);
            eo.putInt(origCdSize); eo.putInt(newCdOff); eo.putShort((short)0);
            os.write(eo.array());
        }
    }

    private static boolean sig(byte[] b, int o, int s) {
        return (b[o]&0xff)==(s&0xff) && (b[o+1]&0xff)==((s>>8)&0xff) && (b[o+2]&0xff)==((s>>16)&0xff) && (b[o+3]&0xff)==((s>>24)&0xff);
    }
    private static int le16(byte[] b, int o) { return (b[o]&0xff) | ((b[o+1]&0xff)<<8); }
    private static int le32(byte[] b, int o) { return (b[o]&0xff) | ((b[o+1]&0xff)<<8) | ((b[o+2]&0xff)<<16) | ((b[o+3]&0xff)<<24); }
}
