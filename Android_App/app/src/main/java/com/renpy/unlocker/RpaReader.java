package com.renpy.unlocker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Đọc trực tiếp bảng mục lục (index) và các file .rpy bên trong file .rpa (RPA-3.0 / RPA-2.0)
 * bằng FileChannel seek trực tiếp, không giải nén toàn bộ archive.
 */
public class RpaReader {

    public interface EntryConsumer {
        void accept(String name, String text, boolean isRpyc);
    }

    public static void readScriptEntries(FileChannel channel, EntryConsumer consumer) throws IOException {
        String header = readLine(channel);
        if (header == null) return;
        String[] parts = header.trim().split("\\s+");
        if (parts.length < 2) return;

        long offset;
        long key;
        if ("RPA-3.0".equals(parts[0]) && parts.length >= 3) {
            offset = Long.parseUnsignedLong(parts[1], 16);
            key = Long.parseUnsignedLong(parts[2], 16);
        } else if ("RPA-2.0".equals(parts[0])) {
            offset = Long.parseUnsignedLong(parts[1], 16);
            key = 0L;
        } else {
            return;
        }

        long fileSize = channel.size();
        if (offset <= 0 || offset >= fileSize) return;
        int indexCompLen = (int) Math.min(fileSize - offset, 64 * 1024 * 1024L);
        byte[] compBytes = readAt(channel, offset, indexCompLen);
        byte[] rawPickle = inflate(compBytes);

        Object parsed = new MiniPickle(rawPickle).parse();
        if (!(parsed instanceof Map<?, ?> indexMap)) return;

        for (Map.Entry<?, ?> entry : indexMap.entrySet()) {
            if (!(entry.getKey() instanceof String name)) continue;
            String norm = name.replace('\\', '/').toLowerCase();
            boolean isRpy = norm.endsWith(".rpy");
            boolean isRpyc = norm.endsWith(".rpyc");
            if ((!isRpy && !isRpyc) || norm.startsWith("tl/")) continue;

            if (!(entry.getValue() instanceof List<?> chunks)) continue;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Object item : chunks) {
                if (!(item instanceof List<?> tuple) || tuple.size() < 2) continue;
                long off = ((Number) tuple.get(0)).longValue() ^ key;
                int dlen = (int) (((Number) tuple.get(1)).longValue() ^ key);
                if (tuple.size() >= 3 && tuple.get(2) instanceof byte[] startBytes && startBytes.length > 0) {
                    out.write(startBytes);
                }
                if (dlen > 0 && dlen <= 32 * 1024 * 1024 && off >= 0 && off + dlen <= fileSize) {
                    out.write(readAt(channel, off, dlen));
                }
            }
            String text = isRpy
                    ? out.toString(StandardCharsets.UTF_8)
                    : extractRpycStrings(out.toByteArray());
            if (text != null && !text.isEmpty()) {
                consumer.accept(name, text, isRpyc);
            }
        }
    }

    public static String extractRpycStrings(byte[] data) {
        if (data == null || data.length < 22) return null;
        String header = new String(data, 0, 10, StandardCharsets.US_ASCII);
        if (!"RENPY RPC2".equals(header)) return null;
        int pos = 10;
        StringBuilder sb = new StringBuilder();
        while (pos + 12 <= data.length) {
            ByteBuffer bb = ByteBuffer.wrap(data, pos, 12).order(ByteOrder.LITTLE_ENDIAN);
            int slot = bb.getInt();
            int start = bb.getInt();
            int length = bb.getInt();
            pos += 12;
            if (slot == 0) break;
            if (slot == 1 && start >= 0 && length > 0 && start + length <= data.length) {
                Inflater inflater = new Inflater();
                try {
                    inflater.setInput(data, start, length);
                    ByteArrayOutputStream dec = new ByteArrayOutputStream();
                    byte[] dbuf = new byte[8192];
                    while (!inflater.finished()) {
                        int c = inflater.inflate(dbuf);
                        if (c == 0 && inflater.needsInput()) break;
                        dec.write(dbuf, 0, c);
                    }
                    byte[] raw = dec.toByteArray();
                    StringBuilder cur = new StringBuilder();
                    for (byte b : raw) {
                        int c = b & 0xFF;
                        if (c >= 32 && c <= 126) {
                            cur.append((char) c);
                        } else {
                            if (cur.length() >= 4) {
                                sb.append(cur).append('\n');
                            }
                            cur.setLength(0);
                        }
                    }
                    if (cur.length() >= 4) sb.append(cur).append('\n');
                } catch (DataFormatException ignored) {
                    return null;
                } finally {
                    inflater.end();
                }
            }
        }
        return sb.toString();
    }

    private static String readLine(FileChannel ch) throws IOException {
        ch.position(0);
        ByteBuffer buf = ByteBuffer.allocate(256);
        int n = ch.read(buf);
        if (n <= 0) return null;
        byte[] arr = buf.array();
        for (int i = 0; i < n; i++) {
            if (arr[i] == '\n') {
                return new String(arr, 0, i, StandardCharsets.UTF_8);
            }
        }
        return new String(arr, 0, n, StandardCharsets.UTF_8);
    }

    private static byte[] readAt(FileChannel ch, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        ch.position(offset);
        while (buf.hasRemaining()) {
            int r = ch.read(buf);
            if (r < 0) break;
        }
        return buf.array();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 2);
        byte[] buffer = new byte[16384];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput()) break;
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IOException("Lỗi giải nén RPA index", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /**
     * Bộ giải mã Python Pickle tối giản (Protocol 0..5) đủ dùng cho RPA index.
     */
    private static final class MiniPickle {
        private static final Object MARK = new Object();
        private final byte[] data;
        private int pos = 0;
        private final List<Object> stack = new ArrayList<>();
        private final Map<Integer, Object> memo = new HashMap<>();
        private int nextMemoId = 0;

        MiniPickle(byte[] data) {
            this.data = data;
        }

        Object parse() throws IOException {
            while (pos < data.length) {
                int op = data[pos++] & 0xFF;
                switch (op) {
                    case 0x80: // PROTO
                        pos++; // version
                        break;
                    case 0x95: // FRAME (8 bytes little-endian length)
                        pos += 8;
                        break;
                    case '(': // MARK
                        stack.add(MARK);
                        break;
                    case '}': // EMPTY_DICT
                        stack.add(new HashMap<>());
                        break;
                    case ']': // EMPTY_LIST
                        stack.add(new ArrayList<>());
                        break;
                    case ')': // EMPTY_TUPLE
                        stack.add(new ArrayList<>());
                        break;
                    case 's': { // SETITEM
                        Object val = pop();
                        Object k = pop();
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> m = (Map<Object, Object>) peek();
                        m.put(k, val);
                        break;
                    }
                    case 'u': { // SETITEMS
                        int markIdx = lastMark();
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> m = (Map<Object, Object>) stack.get(markIdx - 1);
                        for (int i = markIdx + 1; i + 1 < stack.size(); i += 2) {
                            m.put(stack.get(i), stack.get(i + 1));
                        }
                        truncateTo(markIdx);
                        break;
                    }
                    case 'a': { // APPEND
                        Object val = pop();
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) peek();
                        list.add(val);
                        break;
                    }
                    case 'e': { // APPENDS
                        int markIdx = lastMark();
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) stack.get(markIdx - 1);
                        for (int i = markIdx + 1; i < stack.size(); i++) {
                            list.add(stack.get(i));
                        }
                        truncateTo(markIdx);
                        break;
                    }
                    case 't': { // TUPLE
                        int markIdx = lastMark();
                        List<Object> tuple = new ArrayList<>(stack.subList(markIdx + 1, stack.size()));
                        truncateTo(markIdx);
                        stack.add(tuple);
                        break;
                    }
                    case 0x85: { // TUPLE1
                        Object a = pop();
                        List<Object> t = new ArrayList<>(1);
                        t.add(a);
                        stack.add(t);
                        break;
                    }
                    case 0x86: { // TUPLE2
                        Object b = pop();
                        Object a = pop();
                        List<Object> t = new ArrayList<>(2);
                        t.add(a);
                        t.add(b);
                        stack.add(t);
                        break;
                    }
                    case 0x87: { // TUPLE3
                        Object c = pop();
                        Object b = pop();
                        Object a = pop();
                        List<Object> t = new ArrayList<>(3);
                        t.add(a);
                        t.add(b);
                        t.add(c);
                        stack.add(t);
                        break;
                    }
                    case 0x8c: { // SHORT_BINUNICODE
                        int len = data[pos++] & 0xFF;
                        stack.add(readUtf8(len));
                        break;
                    }
                    case 'X': { // BINUNICODE
                        int len = readInt32LE();
                        stack.add(readUtf8(len));
                        break;
                    }
                    case 0x8d: { // BINUNICODE8
                        long len = readInt64LE();
                        stack.add(readUtf8((int) len));
                        break;
                    }
                    case 'C': { // SHORT_BINBYTES
                        int len = data[pos++] & 0xFF;
                        stack.add(readBytes(len));
                        break;
                    }
                    case 'B': { // BINBYTES
                        int len = readInt32LE();
                        stack.add(readBytes(len));
                        break;
                    }
                    case 0x8e: { // BINBYTES8
                        long len = readInt64LE();
                        stack.add(readBytes((int) len));
                        break;
                    }
                    case 'U': { // SHORT_BINSTRING
                        int len = data[pos++] & 0xFF;
                        stack.add(new String(readBytes(len), StandardCharsets.ISO_8859_1));
                        break;
                    }
                    case 'T': { // BINSTRING
                        int len = readInt32LE();
                        stack.add(new String(readBytes(len), StandardCharsets.ISO_8859_1));
                        break;
                    }
                    case 'K': // BININT1
                        stack.add((long) (data[pos++] & 0xFF));
                        break;
                    case 'M': { // BININT2
                        int v = (data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8);
                        stack.add((long) v);
                        break;
                    }
                    case 'J': // BININT
                        stack.add((long) readInt32LE());
                        break;
                    case 0x8a: { // LONG1
                        int n = data[pos++] & 0xFF;
                        stack.add(readBigIntLE(n));
                        break;
                    }
                    case 0x8b: { // LONG4
                        int n = readInt32LE();
                        stack.add(readBigIntLE(n));
                        break;
                    }
                    case 'N': // NONE
                        stack.add(null);
                        break;
                    case 0x94: // MEMOIZE
                        memo.put(nextMemoId++, peek());
                        break;
                    case 'q': { // BINPUT
                        int id = data[pos++] & 0xFF;
                        memo.put(id, peek());
                        break;
                    }
                    case 'r': { // LONG_BINPUT
                        int id = readInt32LE();
                        memo.put(id, peek());
                        break;
                    }
                    case 'h': { // BINGET
                        int id = data[pos++] & 0xFF;
                        stack.add(memo.get(id));
                        break;
                    }
                    case 'j': { // LONG_BINGET
                        int id = readInt32LE();
                        stack.add(memo.get(id));
                        break;
                    }
                    case '.': // STOP
                        return pop();
                    default:
                        throw new IOException("Pickle opcode không hỗ trợ: 0x" + Integer.toHexString(op));
                }
            }
            return stack.isEmpty() ? null : pop();
        }

        private Object pop() {
            return stack.remove(stack.size() - 1);
        }

        private Object peek() {
            return stack.get(stack.size() - 1);
        }

        private int lastMark() throws IOException {
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i) == MARK) return i;
            }
            throw new IOException("Không tìm thấy MARK trong Pickle");
        }

        private void truncateTo(int index) {
            while (stack.size() > index) {
                stack.remove(stack.size() - 1);
            }
        }

        private String readUtf8(int len) {
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        private byte[] readBytes(int len) {
            byte[] b = new byte[len];
            System.arraycopy(data, pos, b, 0, len);
            pos += len;
            return b;
        }

        private int readInt32LE() {
            int v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            pos += 4;
            return v;
        }

        private long readInt64LE() {
            long v = ByteBuffer.wrap(data, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            pos += 8;
            return v;
        }

        private long readBigIntLE(int n) {
            if (n == 0) return 0L;
            byte[] le = readBytes(n);
            byte[] be = new byte[n];
            for (int i = 0; i < n; i++) {
                be[i] = le[n - 1 - i];
            }
            return new BigInteger(be).longValue();
        }
    }
}
