package com.applens.monitor.net;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/** Minimal, allocation-light DNS message parser used by the capture VPN. */
public final class DnsMessage {

    public static final int TYPE_A = 1;
    public static final int TYPE_NS = 2;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_SOA = 6;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_MX = 15;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;
    public static final int TYPE_HTTPS = 65;
    public static final int TYPE_ANY = 255;

    public static final class Question {
        public String name = "";
        public int type;
        public int clazz;
    }

    public static final class Record {
        public String name = "";
        public int type;
        public int clazz;
        public long ttl;
        public String data = "";
    }

    public int id;
    public int flags;
    public int qdCount;
    public int anCount;
    public int nsCount;
    public int arCount;
    public final List<Question> questions = new ArrayList<>();
    public final List<Record> answers = new ArrayList<>();

    public boolean isResponse() {
        return (flags & 0x8000) != 0;
    }

    public int rcode() {
        return flags & 0x000F;
    }

    public int truncated() {
        return (flags & 0x0200) != 0 ? 1 : 0;
    }

    public Question primary() {
        return questions.isEmpty() ? null : questions.get(0);
    }

    /** Builds a TC=1 response containing only the question, used when a UDP answer will not fit. */
    public static byte[] truncate(byte[] message, int offset, int length) {
        if (message == null || length < 12) {
            return null;
        }
        try {
            Message m = parse(message, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeShort(out, m.id);
            int flags = (m.flags | 0x0200) & 0xFFFF;
            writeShort(out, flags);
            Question q = m.primary();
            writeShort(out, q == null ? 0 : 1);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, 0);
            if (q != null) {
                writeName(out, q.name);
                writeShort(out, q.type);
                writeShort(out, q.clazz);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Human readable DNS record type. */
    public static String typeName(int type) {
        switch (type) {
            case TYPE_A:
                return "A";
            case TYPE_NS:
                return "NS";
            case TYPE_CNAME:
                return "CNAME";
            case TYPE_SOA:
                return "SOA";
            case TYPE_PTR:
                return "PTR";
            case TYPE_MX:
                return "MX";
            case TYPE_TXT:
                return "TXT";
            case TYPE_AAAA:
                return "AAAA";
            case TYPE_SRV:
                return "SRV";
            case TYPE_HTTPS:
                return "HTTPS";
            case TYPE_ANY:
                return "ANY";
            default:
                return "TYPE" + type;
        }
    }

    public static Message parse(byte[] buf, int offset, int length) {
        Message m = new Message();
        if (buf == null || length < 12) {
            return m;
        }
        int end = Math.min(buf.length, offset + length);
        m.id = u16(buf, offset);
        m.flags = u16(buf, offset + 2);
        m.qdCount = u16(buf, offset + 4);
        m.anCount = u16(buf, offset + 6);
        m.nsCount = u16(buf, offset + 8);
        m.arCount = u16(buf, offset + 10);
        int p = offset + 12;
        try {
            for (int i = 0; i < m.qdCount && p < end; i++) {
                Question q = new Question();
                int[] consumed = new int[1];
                q.name = readName(buf, p, consumed);
                p += consumed[0];
                if (p + 4 > end) {
                    break;
                }
                q.type = u16(buf, p);
                q.clazz = u16(buf, p + 2);
                p += 4;
                m.questions.add(q);
            }
            int total = m.anCount + m.nsCount + m.arCount;
            for (int i = 0; i < total && p < end; i++) {
                Record r = new Record();
                int[] consumed = new int[1];
                r.name = readName(buf, p, consumed);
                p += consumed[0];
                if (p + 10 > end) {
                    break;
                }
                r.type = u16(buf, p);
                r.clazz = u16(buf, p + 2);
                r.ttl = u32(buf, p + 4);
                int rdLength = u16(buf, p + 8);
                p += 10;
                if (p + rdLength > end) {
                    break;
                }
                r.data = rdata(buf, p, rdLength, r.type);
                p += rdLength;
                m.answers.add(r);
            }
        } catch (Throwable ignored) {
            // return whatever was parsed
        }
        return m;
    }

    private static String rdata(byte[] buf, int p, int len, int type) {
        try {
            switch (type) {
                case TYPE_A:
                    if (len == 4) {
                        return (buf[p] & 0xFF) + "." + (buf[p + 1] & 0xFF) + "."
                                + (buf[p + 2] & 0xFF) + "." + (buf[p + 3] & 0xFF);
                    }
                    return "";
                case TYPE_AAAA:
                    if (len == 16) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 16; i += 2) {
                            if (i > 0) {
                                sb.append(':');
                            }
                            sb.append(Integer.toHexString(((buf[p + i] & 0xFF) << 8) | (buf[p + i + 1] & 0xFF)));
                        }
                        return sb.toString();
                    }
                    return "";
                case TYPE_CNAME:
                case TYPE_PTR:
                case TYPE_NS: {
                    int[] consumed = new int[1];
                    return readName(buf, p, consumed);
                }
                case TYPE_TXT: {
                    int pos = p;
                    StringBuilder sb = new StringBuilder();
                    while (pos < p + len) {
                        int l = buf[pos] & 0xFF;
                        if (pos + 1 + l > p + len) {
                            break;
                        }
                        sb.append(new String(buf, pos + 1, l));
                        pos += 1 + l;
                    }
                    return sb.toString();
                }
                default:
                    return "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** Reads a (possibly compressed) domain name; {@code consumed} receives the encoded length. */
    public static String readName(byte[] buf, int offset, int[] consumed) {
        StringBuilder sb = new StringBuilder();
        int p = offset;
        int jumped = 0;
        int guard = 0;
        consumed[0] = 0;
        while (p < buf.length && guard++ < 128) {
            int len = buf[p] & 0xFF;
            if (len == 0) {
                p++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                if (p + 1 >= buf.length) {
                    break;
                }
                int pointer = ((len & 0x3F) << 8) | (buf[p + 1] & 0xFF);
                if (jumped == 0) {
                    consumed[0] = (p + 2) - offset;
                    jumped = 1;
                }
                p = pointer;
                continue;
            }
            int start = p + 1;
            int stop = Math.min(buf.length, start + len);
            if (start >= stop) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(ascii(buf, start, stop));
            p = stop;
        }
        if (consumed[0] == 0) {
            consumed[0] = p - offset;
        }
        return sb.toString();
    }

    public static void writeName(ByteArrayOutputStream out, String name) {
        if (name == null || name.isEmpty()) {
            out.write(0);
            return;
        }
        for (String label : name.split("\\.")) {
            byte[] raw = asciiBytes(label);
            int len = Math.min(raw.length, 63);
            out.write(len);
            out.write(raw, 0, len);
        }
        out.write(0);
    }

    public static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static int u16(byte[] b, int off) {
        if (off + 1 >= b.length) {
            return 0;
        }
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long u32(byte[] b, int off) {
        if (off + 3 >= b.length) {
            return 0;
        }
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String ascii(byte[] b, int start, int stop) {
        StringBuilder sb = new StringBuilder(stop - start);
        for (int i = start; i < stop; i++) {
            int c = b[i] & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '?');
        }
        return sb.toString();
    }

    public static byte[] asciiBytes(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }
}
