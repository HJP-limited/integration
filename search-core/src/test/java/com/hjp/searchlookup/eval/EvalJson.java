package com.hjp.searchlookup.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read a frozen evaluation input and write a report, parsed properly.
 *
 * {@code search-core} has no JSON dependency and this evaluator is not going to add one to the
 * production classpath. What it will not do is read JSON with regular expressions: the previous
 * adapter did, and the pattern matched at every nesting depth at once, so a query file that
 * happened to nest one level deeper than the card file came back as a single object. That defect
 * cost a run. This is a plain recursive-descent parser instead — longer, and correct about
 * structure by construction.
 *
 * <p>Numbers are kept as {@link Double} and booleans as {@link Boolean}, so a report round-trips
 * with its types intact rather than turning every value into a string.
 */
final class EvalJson {

    private final String source;
    private int position;

    private EvalJson(String source) {
        this.source = source;
    }

    /** Parses a whole document: a map, a list, or a scalar. */
    static Object parse(String text) {
        EvalJson parser = new EvalJson(text == null ? "" : text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != parser.source.length()) {
            throw new IllegalArgumentException(
                    "trailing content at offset " + parser.position);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> parseArray(String text) {
        Object value = parse(text);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("expected a JSON array, got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return (List<Object>) value;
    }

    private Object readValue() {
        if (position >= source.length()) throw new IllegalArgumentException("unexpected end of input");
        char c = source.charAt(position);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        position++; // {
        skipWhitespace();
        if (peek() == '}') { position++; return out; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (peek() != ':') throw new IllegalArgumentException("expected ':' at " + position);
            position++;
            skipWhitespace();
            out.put(key, readValue());
            skipWhitespace();
            char c = peek();
            position++;
            if (c == '}') return out;
            if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at " + (position - 1));
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        position++; // [
        skipWhitespace();
        if (peek() == ']') { position++; return out; }
        while (true) {
            skipWhitespace();
            out.add(readValue());
            skipWhitespace();
            char c = peek();
            position++;
            if (c == ']') return out;
            if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at " + (position - 1));
        }
    }

    private String readString() {
        if (peek() != '"') throw new IllegalArgumentException("expected a string at " + position);
        position++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= source.length()) throw new IllegalArgumentException("unterminated string");
            char c = source.charAt(position++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            char escape = source.charAt(position++);
            switch (escape) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    out.append((char) Integer.parseInt(source.substring(position, position + 4), 16));
                    position += 4;
                    break;
                default: throw new IllegalArgumentException("bad escape \\" + escape);
            }
        }
    }

    private Double readNumber() {
        int start = position;
        while (position < source.length() && "+-.eE0123456789".indexOf(source.charAt(position)) >= 0) {
            position++;
        }
        if (start == position) {
            throw new IllegalArgumentException("expected a value at " + position);
        }
        return Double.valueOf(source.substring(start, position));
    }

    private void expect(String literal) {
        if (!source.startsWith(literal, position)) {
            throw new IllegalArgumentException("expected " + literal + " at " + position);
        }
        position += literal.length();
    }

    private char peek() {
        if (position >= source.length()) throw new IllegalArgumentException("unexpected end of input");
        return source.charAt(position);
    }

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
    }

    // ---- writing -------------------------------------------------------------------------------

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        String safe = value == null ? "" : value;
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    static String stringArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    /** Formats a rate with enough digits that a gate comparison is not decided by rounding. */
    static String rate(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    static int integer(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("expected a number for \"" + key + "\"");
        }
        double raw = (Double) value;
        if (raw != Math.rint(raw)) {
            throw new IllegalArgumentException("expected a whole number for \"" + key + "\", got " + raw);
        }
        return (int) raw;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected an object for \"" + key + "\"");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("expected an array for \"" + key + "\"");
        }
        return (List<Object>) value;
    }
}
