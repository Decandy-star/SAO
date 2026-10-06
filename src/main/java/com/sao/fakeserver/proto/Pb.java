package com.sao.fakeserver.proto;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * protobuf-net 线格式与 proto2 兼容：varint / length-delimited / fixed32。
 * 不生成 .proto，避免本机没 protoc。
 */
public final class Pb {
    private Pb() {
    }

    @FunctionalInterface
    public interface Writer {
        void write(CodedOutputStream out) throws IOException;
    }

    public static byte[] write(Writer writer) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bos);
        try {
            writer.write(out);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static void int32(CodedOutputStream out, int field, int value) throws IOException {
        if (value != 0) {
            out.writeInt32(field, value);
        }
    }

    public static void int32Always(CodedOutputStream out, int field, int value) throws IOException {
        out.writeInt32(field, value);
    }

    public static void bool(CodedOutputStream out, int field, boolean value) throws IOException {
        if (value) {
            out.writeBool(field, true);
        }
    }

    public static void boolAlways(CodedOutputStream out, int field, boolean value) throws IOException {
        out.writeBool(field, value);
    }

    public static void string(CodedOutputStream out, int field, String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            out.writeString(field, value);
        }
    }

    public static void stringAlways(CodedOutputStream out, int field, String value) throws IOException {
        out.writeString(field, value == null ? "" : value);
    }

    public static void float32(CodedOutputStream out, int field, float value) throws IOException {
        if (value != 0f) {
            out.writeFloat(field, value);
        }
    }

    public static void float32Always(CodedOutputStream out, int field, float value) throws IOException {
        out.writeFloat(field, value);
    }

    public static void bytes(CodedOutputStream out, int field, byte[] nested) throws IOException {
        if (nested != null && nested.length > 0) {
            out.writeByteArray(field, nested);
        }
    }

    /** 嵌套消息即使空也要写出，避免客户端字段保持 null 后 NRE（如 JJC 2001.TargetBriefInfos）。 */
    public static void bytesAlways(CodedOutputStream out, int field, byte[] nested) throws IOException {
        out.writeByteArray(field, nested == null ? new byte[0] : nested);
    }

    public static Fields read(byte[] body) {
        Fields fields = new Fields();
        if (body == null || body.length == 0) {
            return fields;
        }
        CodedInputStream in = CodedInputStream.newInstance(body);
        try {
            while (!in.isAtEnd()) {
                int tag = in.readTag();
                if (tag == 0) {
                    break;
                }
                int field = WireFormat.getTagFieldNumber(tag);
                int wire = WireFormat.getTagWireType(tag);
                Object value;
                if (wire == WireFormat.WIRETYPE_VARINT) {
                    value = in.readInt64();
                } else if (wire == WireFormat.WIRETYPE_FIXED32) {
                    value = Float.intBitsToFloat(in.readFixed32());
                } else if (wire == WireFormat.WIRETYPE_FIXED64) {
                    value = in.readFixed64();
                } else if (wire == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    value = in.readByteArray();
                } else {
                    in.skipField(tag);
                    continue;
                }
                fields.add(field, value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return fields;
    }

    public static final class Fields {
        private final Map<Integer, List<Object>> map = new LinkedHashMap<>();

        void add(int field, Object value) {
            map.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
        }

        public int getInt(int field, int def) {
            List<Object> list = map.get(field);
            if (list == null || list.isEmpty()) {
                return def;
            }
            Object v = list.get(0);
            if (v instanceof Long) {
                return ((Long) v).intValue();
            }
            if (v instanceof Integer) {
                return (Integer) v;
            }
            return def;
        }

        public List<Integer> getInts(int field) {
            List<Object> list = map.get(field);
            List<Integer> out = new ArrayList<>();
            if (list == null) {
                return out;
            }
            for (Object v : list) {
                if (v instanceof Long) {
                    out.add(Integer.valueOf(((Long) v).intValue()));
                } else if (v instanceof Integer) {
                    out.add((Integer) v);
                }
            }
            return out;
        }

        public boolean getBool(int field) {
            return getInt(field, 0) != 0;
        }

        public String getString(int field) {
            List<String> all = getStrings(field);
            return all.isEmpty() ? "" : all.get(0);
        }

        public List<String> getStrings(int field) {
            List<Object> list = map.get(field);
            List<String> out = new ArrayList<>();
            if (list == null) {
                return out;
            }
            for (Object v : list) {
                if (v instanceof byte[]) {
                    out.add(new String((byte[]) v, StandardCharsets.UTF_8));
                } else if (v != null) {
                    out.add(String.valueOf(v));
                }
            }
            return out;
        }

        public byte[] getBytes(int field) {
            List<byte[]> list = getBytesList(field);
            return list.isEmpty() ? new byte[0] : list.get(0);
        }

        /** 已解析到的 protobuf field 号（按出现顺序），用于确认有无漏字段。 */
        public java.util.Set<Integer> fieldKeys() {
            return map.keySet();
        }

        public List<byte[]> getBytesList(int field) {
            List<Object> list = map.get(field);
            List<byte[]> out = new ArrayList<>();
            if (list == null) {
                return out;
            }
            for (Object v : list) {
                if (v instanceof byte[]) {
                    out.add((byte[]) v);
                }
            }
            return out;
        }

        public float getFloat(int field, float def) {
            List<Object> list = map.get(field);
            if (list == null || list.isEmpty()) {
                return def;
            }
            Object v = list.get(0);
            if (v instanceof Float) {
                return (Float) v;
            }
            if (v instanceof Double) {
                return ((Double) v).floatValue();
            }
            return def;
        }
    }
}
