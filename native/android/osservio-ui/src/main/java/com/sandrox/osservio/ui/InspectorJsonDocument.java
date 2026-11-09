package com.sandrox.osservio.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** A bounded lexical index. Original number tokens, duplicate keys and ordering remain intact. */
final class InspectorJsonDocument {
    static final int MAX_UNITS = 65_536, MAX_NODES = 4_096, MAX_DEPTH = 48;
    enum Failure { NOT_JSON, INVALID, LIMITED, CANCELLED }
    enum Kind { OBJECT, ARRAY, STRING, NUMBER, LITERAL }
    static final class ParseFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final Failure reason;
        ParseFailure(Failure reason) { super(reason.name()); this.reason = reason; }
    }
    static final class Node {
        final Kind kind;
        final int parent, depth, keyStart, keyEnd, start;
        int end;
        final List<Integer> children = new ArrayList<>();
        Node(Kind kind, int parent, int depth, int keyStart, int keyEnd, int start) {
            this.kind = kind; this.parent = parent; this.depth = depth;
            this.keyStart = keyStart; this.keyEnd = keyEnd; this.start = start;
        }
        boolean expandable() { return !children.isEmpty(); }
    }
    static final class Row {
        final int node;
        final boolean closing;
        Row(int node, boolean closing) { this.node = node; this.closing = closing; }
        long identity() { return closing ? -node - 1L : node; }
    }
    final String source;
    final List<Node> nodes;
    private InspectorJsonDocument(String source, List<Node> nodes) {
        this.source = source; this.nodes = Collections.unmodifiableList(nodes);
    }
    static InspectorJsonDocument parse(String source, BooleanSupplier cancelled) throws ParseFailure {
        if (source.length() > MAX_UNITS) throw new ParseFailure(Failure.LIMITED);
        Parser parser = new Parser(source, cancelled); parser.whitespace();
        if (parser.current() != '{' && parser.current() != '[') throw new ParseFailure(Failure.NOT_JSON);
        parser.value(-1, -1, -1, 0); parser.whitespace();
        if (parser.position != source.length()) throw new ParseFailure(Failure.INVALID);
        return new InspectorJsonDocument(source, parser.nodes);
    }
    List<Row> rows(Set<Integer> expanded) {
        List<Row> result = new ArrayList<>(); appendRows(0, expanded, result); return result;
    }
    private void appendRows(int index, Set<Integer> expanded, List<Row> result) {
        result.add(new Row(index, false)); Node node = nodes.get(index);
        if (node.expandable() && (index == 0 || expanded.contains(index))) {
            for (int child : node.children) appendRows(child, expanded, result);
            result.add(new Row(index, true));
        }
    }
    Set<Integer> ancestors(int index) {
        Set<Integer> result = new HashSet<>();
        for (int parent = nodes.get(index).parent; parent >= 0; parent = nodes.get(parent).parent) result.add(parent);
        return result;
    }
    int nodeAt(int offset) {
        int found = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.keyStart >= 0 && offset >= node.keyStart && offset < node.keyEnd) return i;
            if (offset >= node.start && offset < node.end) found = i;
        }
        return found;
    }
    String original(int index) { Node node = nodes.get(index); return source.substring(node.start, node.end); }
    String key(int index) {
        Node node = nodes.get(index); return node.keyStart < 0 ? "" : preview(node.keyStart, node.keyEnd, 96) + ": ";
    }
    String token(Row row, Set<Integer> expanded) {
        Node node = nodes.get(row.node);
        if (row.closing) return node.kind == Kind.OBJECT ? "}" : "]";
        if (node.kind != Kind.OBJECT && node.kind != Kind.ARRAY) return preview(node.start, node.end, 256);
        String open = node.kind == Kind.OBJECT ? "{" : "[", close = node.kind == Kind.OBJECT ? "}" : "]";
        if (node.children.isEmpty()) return open + close;
        if (row.node == 0 || expanded.contains(row.node)) return open;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(2, node.children.size()); i++) {
            int child = node.children.get(i); Node value = nodes.get(child); String brief;
            if (value.kind == Kind.OBJECT) brief = value.children.isEmpty() ? "{}" : "{…}";
            else if (value.kind == Kind.ARRAY) brief = value.children.isEmpty() ? "[]" : "[…]";
            else brief = preview(value.start, value.end, 48);
            parts.add(key(child) + brief);
        }
        if (node.children.size() > 2) parts.add("…");
        return open + " " + String.join(", ", parts) + " " + close;
    }
    String comma(Row row, Set<Integer> expanded) {
        Node node = nodes.get(row.node);
        if (node.parent < 0 || (!row.closing && node.expandable() && expanded.contains(row.node))) return "";
        List<Integer> siblings = nodes.get(node.parent).children;
        return siblings.get(siblings.size() - 1) == row.node ? "" : ",";
    }
    private String preview(int start, int end, int limit) {
        if (end - start <= limit) return source.substring(start, end);
        int cut = start + limit; if (Character.isHighSurrogate(source.charAt(cut - 1))) cut--;
        return source.substring(start, cut) + "…";
    }

    private static final class Parser {
        final String source;
        final BooleanSupplier cancelled;
        final List<Node> nodes = new ArrayList<>();
        int position;
        Parser(String source, BooleanSupplier cancelled) { this.source = source; this.cancelled = cancelled; }
        char current() { return position < source.length() ? source.charAt(position) : 0; }
        void step() throws ParseFailure {
            position++;
            if ((position & 255) == 0 && cancelled.getAsBoolean()) throw new ParseFailure(Failure.CANCELLED);
        }
        void whitespace() { while (current() == ' ' || current() == '\n' || current() == '\r' || current() == '\t') position++; }
        void take(char value) throws ParseFailure { if (current() != value) throw new ParseFailure(Failure.INVALID); step(); }
        int value(int parent, int keyStart, int keyEnd, int depth) throws ParseFailure {
            if (cancelled.getAsBoolean()) throw new ParseFailure(Failure.CANCELLED);
            if (depth > MAX_DEPTH || nodes.size() >= MAX_NODES) throw new ParseFailure(Failure.LIMITED);
            whitespace(); int start = position; char first = current(); Kind kind;
            if (first == '{') kind = Kind.OBJECT;
            else if (first == '[') kind = Kind.ARRAY;
            else if (first == '"') kind = Kind.STRING;
            else if (first == '-' || digit(first)) kind = Kind.NUMBER;
            else if (first == 't' || first == 'f' || first == 'n') kind = Kind.LITERAL;
            else throw new ParseFailure(Failure.INVALID);
            int id = nodes.size(); Node node = new Node(kind, parent, depth, keyStart, keyEnd, start); nodes.add(node);
            switch (kind) {
                case OBJECT: case ARRAY:
                    char close = kind == Kind.OBJECT ? '}' : ']'; step(); whitespace();
                    if (current() != close) while (true) {
                        int memberStart = -1, memberEnd = -1;
                        if (kind == Kind.OBJECT) { memberStart = position; string(); memberEnd = position; whitespace(); take(':'); }
                        node.children.add(value(id, memberStart, memberEnd, depth + 1)); whitespace();
                        if (current() == close) break;
                        take(','); whitespace();
                    }
                    take(close); break;
                case STRING: string(); break;
                case NUMBER: number(); break;
                case LITERAL:
                    String word = first == 't' ? "true" : first == 'f' ? "false" : "null";
                    for (int i = 0; i < word.length(); i++) take(word.charAt(i));
                    break;
                default: throw new AssertionError(kind);
            }
            node.end = position; return id;
        }
        void string() throws ParseFailure {
            take('"');
            while (position < source.length()) {
                char unit = current(); step(); if (unit == '"') return;
                if (unit < 32) throw new ParseFailure(Failure.INVALID);
                if (unit == '\\') {
                    char escape = current();
                    if (escape == 'u') {
                        step(); for (int i = 0; i < 4; i++) {
                            char hex = current();
                            if (!digit(hex) && !(hex >= 'a' && hex <= 'f') && !(hex >= 'A' && hex <= 'F')) throw new ParseFailure(Failure.INVALID);
                            step();
                        }
                    } else if (escape != 0 && "\"\\/bfnrt".indexOf(escape) >= 0) step();
                    else throw new ParseFailure(Failure.INVALID);
                }
            }
            throw new ParseFailure(Failure.INVALID);
        }
        void number() throws ParseFailure {
            if (current() == '-') step();
            if (current() == '0') step(); else { if (current() < '1' || current() > '9') throw new ParseFailure(Failure.INVALID); digits(); }
            if (current() == '.') { step(); digits(); }
            if (current() == 'e' || current() == 'E') { step(); if (current() == '+' || current() == '-') step(); digits(); }
        }
        void digits() throws ParseFailure { if (!digit(current())) throw new ParseFailure(Failure.INVALID); while (digit(current())) step(); }
        static boolean digit(char unit) { return unit >= '0' && unit <= '9'; }
    }
}
