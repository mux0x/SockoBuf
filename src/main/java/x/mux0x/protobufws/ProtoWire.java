package x.mux0x.protobufws;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Protobuf wire-format tokenizer with recursive descent:
 * - Parses length-delimited fields as embedded messages when they themselves look like protobuf.
 * - Surfaces UTF-8 strings at any depth; preserves binary fields as-is.
 * Not schema-aware; no groups (wire types 3/4).
 */
public final class ProtoWire {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_EMBEDDED_BYTES = 1_048_576; // 1MB safety

    public static final class Node {
        public int fieldNumber;
        public int wireType; // 0=varint,1=fixed64,2=len,5=fixed32

        // Raw content holders (exactly as captured) — we prefer to preserve bytes
        public byte[] rawVarintBytes;     // for wireType 0
        public byte[] fixedBytes;         // for 1 and 5

        // For wire type 2:
        public byte[] lengthDelimited;    // if not a parsed submessage or editable string set here
        public List<Node> children;       // if parsed as embedded message

        public Node(int fieldNumber, int wireType) {
            this.fieldNumber = fieldNumber;
            this.wireType = wireType;
        }

        public boolean isEmbeddedMessage() { return children != null; }
        public boolean isLeafLenDelimited() { return wireType == 2 && children == null; }
    }

    public static final class ParseResult {
        public final Node root;
        public ParseResult(Node root) { this.root = root; }
    }

    // ————— Public API —————

    public static boolean looksLikeProtobuf(byte[] data) {
        try {
            int i = 0, n = data.length;
            while (i < n) {
                Varint key = readVarint(data, i);
                if (key.len <= 0) return false;
                i += key.len;

                int wireType = (int)(key.value & 0x7);
                if (wireType == 3 || wireType == 4) return false; // groups unsupported

                switch (wireType) {
                    case 0: { // varint
                        Varint v = readVarint(data, i);
                        if (v.len <= 0) return false;
                        i += v.len;
                        break;
                    }
                    case 1: // fixed64
                        i += 8; if (i > n) return false; break;
                    case 2: { // length-delimited
                        Varint l = readVarint(data, i);
                        if (l.len <= 0) return false;
                        int size = asInt(l.value);
                        if (size < 0) return false;
                        i += l.len + size;
                        if (i > n) return false;
                        break;
                    }
                    case 5: // fixed32
                        i += 4; if (i > n) return false; break;
                    default:
                        return false;
                }
            }
            return i == n;
        } catch (Exception e) {
            return false;
        }
    }

    public static ParseResult parse(byte[] data) {
        Node root = new Node(0, -1);
        root.children = parseAt(data, 0, data.length, 0);
        return new ParseResult(root);
    }

    public static byte[] serialize(Node root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChildren(out, root.children);
        return out.toByteArray();
    }

    /** Scan tree and collect length-delimited leaves (both utf8-printable and binary), depth-first. */
    public static List<NodePath> collectEditableLeaves(Node root) {
        List<NodePath> out = new ArrayList<>();
        walk(root, new ArrayList<>(), out);
        return out;
    }

    public static boolean isUtf8Printable(byte[] data) {
        try {
            String s = new String(data, StandardCharsets.UTF_8);
            byte[] round = s.getBytes(StandardCharsets.UTF_8);
            if (round.length != data.length) return false;
            int printable = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\r' || c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7F)) printable++;
            }
            return s.length() == 0 || printable >= Math.max(1, (int)(s.length() * 0.8));
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] tryDecodeBase64(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 4) return null;
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ————— Implementation —————

    public static final class NodePath {
        public final Node node;
        public final String path;      // e.g., "2.1.3" (field numbers from root)
        public final boolean utf8;     // true if printable UTF-8

        public NodePath(Node node, String path, boolean utf8) {
            this.node = node;
            this.path = path;
            this.utf8 = utf8;
        }
    }

    private static void walk(Node node, List<Integer> prefix, List<NodePath> out) {
        if (node.children != null) {
            for (Node c : node.children) {
                List<Integer> next = new ArrayList<>(prefix);
                if (c.wireType != -1) next.add(c.fieldNumber);
                walk(c, next, out);
            }
            return;
        }
        if (node.isLeafLenDelimited()) {
            String path = join(prefix);
            boolean utf8 = isUtf8Printable(node.lengthDelimited);
            out.add(new NodePath(node, path, utf8));
        }
    }

    private static String join(List<Integer> nums) {
        if (nums.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nums.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(nums.get(i));
        }
        return sb.toString();
    }

    private static List<Node> parseAt(byte[] a, int off, int end, int depth) {
        List<Node> nodes = new ArrayList<>();
        int i = off;
        while (i < end) {
            Varint key = readVarint(a, i);
            i += key.len;

            int fieldNumber = (int)(key.value >>> 3);
            int wireType = (int)(key.value & 0x7);

            Node n = new Node(fieldNumber, wireType);

            switch (wireType) {
                case 0: { // varint
                    Varint v = readVarint(a, i);
                    n.rawVarintBytes = slice(a, i, i + v.len);
                    i += v.len;
                    break;
                }
                case 1: { // fixed64
                    n.fixedBytes = slice(a, i, i + 8);
                    i += 8;
                    break;
                }
                case 2: { // length-delimited (string, bytes, or embedded message)
                    Varint l = readVarint(a, i);
                    int size = asInt(l.value);
                    i += l.len;
                    byte[] payload = slice(a, i, i + size);
                    i += size;

                    boolean recursed = false;
                    if (depth < MAX_DEPTH && size <= MAX_EMBEDDED_BYTES && looksLikeProtobuf(payload)) {
                        // Try parse as embedded message
                        List<Node> kids = parseAt(payload, 0, payload.length, depth + 1);
                        if (!kids.isEmpty()) {
                            n.children = kids;
                            recursed = true;
                        }
                    }
                    if (!recursed) {
                        n.lengthDelimited = payload;
                    }
                    break;
                }
                case 5: { // fixed32
                    n.fixedBytes = slice(a, i, i + 4);
                    i += 4;
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unsupported wire type: " + wireType);
            }

            nodes.add(n);
        }
        return nodes;
    }

    private static void writeChildren(ByteArrayOutputStream out, List<Node> children) {
        if (children == null) return;
        for (Node n : children) {
            long keyVal = ((long) n.fieldNumber << 3) | (n.wireType & 0x7);
            writeVarint(out, keyVal);

            switch (n.wireType) {
                case 0:
                    // write original varint bytes verbatim
                    out.write(n.rawVarintBytes, 0, n.rawVarintBytes.length);
                    break;
                case 1:
                case 5:
                    out.write(n.fixedBytes, 0, n.fixedBytes.length);
                    break;
                case 2:
                    byte[] payload;
                    if (n.children != null) {
                        ByteArrayOutputStream inner = new ByteArrayOutputStream();
                        writeChildren(inner, n.children);
                        payload = inner.toByteArray();
                    } else {
                        payload = (n.lengthDelimited != null) ? n.lengthDelimited : new byte[0];
                    }
                    writeVarint(out, payload.length);
                    out.write(payload, 0, payload.length);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported wire type: " + n.wireType);
            }
        }
    }

    private static byte[] slice(byte[] a, int s, int e) {
        byte[] b = new byte[e - s];
        System.arraycopy(a, s, b, 0, b.length);
        return b;
    }

    private static int asInt(long v) {
        if (v > Integer.MAX_VALUE) throw new IllegalArgumentException("Size too large");
        return (int) v;
    }

    private static final class Varint {
        final long value;
        final int len;
        Varint(long value, int len) { this.value = value; this.len = len; }
    }

    private static Varint readVarint(byte[] a, int off) {
        long val = 0;
        int shift = 0;
        int i = off;
        while (i < a.length && shift <= 63) {
            int b = a[i++] & 0xFF;
            val |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new Varint(val, i - off);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Malformed varint");
    }

    private static void writeVarint(ByteArrayOutputStream out, long v) {
        long x = v;
        while ((x & ~0x7FL) != 0) {
            out.write((int) ((x & 0x7F) | 0x80));
            x >>>= 7;
        }
        out.write((int) x);
    }
}
