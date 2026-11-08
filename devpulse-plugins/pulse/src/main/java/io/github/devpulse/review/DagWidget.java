package io.github.devpulse.review;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.GraphemeClusters;
import dev.tamboui.widget.Widget;
import io.github.devpulse.execution.PulsePlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DagWidget implements Widget {
    private static final int NODE_WIDTH = 18;
    private static final int COLUMN_GAP = 8;
    private static final int ROW_HEIGHT = 4;
    private static final Style NORMAL = Style.create().gray();
    private static final Style SELECTED = Style.create().yellow().bold();
    private static final Style BEFORE = Style.create().green().bold();
    private static final Style AFTER = Style.create().magenta().bold();

    private record Node(String name, int number, int depth, int x, int y) {}
    private record Edge(Node source, Node target) {}

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final HashMap<String, Node> byName = new HashMap<>();
    private final HashMap<String, List<String>> prerequisites = new HashMap<>();
    private final HashMap<String, List<String>> dependents = new HashMap<>();
    private final int width;
    private final int height;
    private String selected;
    private Set<String> before = Set.of();
    private Set<String> after = Set.of();
    private int offsetX;
    private int offsetY;
    private int viewportWidth;
    private int viewportHeight;
    private boolean centerPending = true;

    DagWidget(PulsePlan plan) {
        var rows = new HashMap<Integer, Integer>();
        for (var task : plan.tasks()) {
            var depth = task.needs().stream()
                    .mapToInt(name -> byName.get(name).depth() + 1)
                    .max().orElse(0);
            var row = rows.getOrDefault(depth, 0);
            rows.put(depth, row + 1);
            var node = new Node(task.name(), nodes.size() + 1, depth,
                    depth * (NODE_WIDTH + COLUMN_GAP), 2 + row * ROW_HEIGHT);
            nodes.add(node);
            byName.put(node.name(), node);
            prerequisites.put(node.name(), task.needs());
        }
        for (var node : nodes) {
            for (var name : prerequisites.get(node.name())) {
                edges.add(new Edge(byName.get(name), node));
                dependents.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(node.name());
            }
        }
        width = nodes.stream().mapToInt(node -> node.x() + NODE_WIDTH)
                .max().orElse(0);
        height = nodes.stream().mapToInt(node -> node.y() + 3)
                .max().orElse(0);
        select(nodes.getFirst().name());
    }

    int depth(String name) {
        return byName.get(name).depth();
    }

    void select(String name) {
        selected = name;
        before = reachable(name, prerequisites);
        after = reachable(name, dependents);
        centerPending = true;
    }

    void center() {
        centerPending = true;
    }

    void pan(int dx, int dy) {
        centerPending = false;
        offsetX = clamp(offsetX + dx, width - viewportWidth);
        offsetY = clamp(offsetY + dy, height - viewportHeight);
    }

    String position() {
        return offsetX + "," + offsetY;
    }

    @Override
    public void render(Rect area, Buffer buffer) {
        viewportWidth = area.width();
        viewportHeight = area.height();
        if (viewportWidth == 0 || viewportHeight == 0) {
            return;
        }
        if (centerPending) {
            var node = byName.get(selected);
            offsetX = clamp(node.x() + NODE_WIDTH / 2 - viewportWidth / 2,
                    width - viewportWidth);
            offsetY = clamp(node.y() + 1 - viewportHeight / 2,
                    height - viewportHeight);
            centerPending = false;
        }
        offsetX = clamp(offsetX, width - viewportWidth);
        offsetY = clamp(offsetY, height - viewportHeight);
        var strokes = new byte[viewportHeight][viewportWidth];
        var priorities = new byte[viewportHeight][viewportWidth];
        for (var edge : edges) {
            var priority = (byte) (before.contains(edge.target().name())
                    || edge.target().name().equals(selected) ? 2
                    : after.contains(edge.source().name())
                    || edge.source().name().equals(selected) ? 1 : 0);
            route(edge, strokes, priorities, priority);
        }
        for (var y = 0; y < viewportHeight; y++) {
            for (var x = 0; x < viewportWidth; x++) {
                var stroke = strokes[y][x];
                if (stroke != 0) {
                    var symbol = (stroke & 4) != 0 ? ">"
                            : stroke == 3 ? "+" : stroke == 1 ? "-" : "|";
                    var style = priorities[y][x] == 2 ? BEFORE
                            : priorities[y][x] == 1 ? AFTER : NORMAL;
                    buffer.setString(area.left() + x, area.top() + y,
                            symbol, style);
                }
            }
        }
        for (var node : nodes) {
            write(area, buffer, node.x(), 0, "L" + node.depth(), NORMAL);
            var style = node.name().equals(selected) ? SELECTED
                    : before.contains(node.name()) ? BEFORE
                    : after.contains(node.name()) ? AFTER : NORMAL;
            var label = node.number() + " " + node.name();
            if (CharWidth.of(label) > NODE_WIDTH - 2) {
                label = CharWidth.substringByWidth(label, NODE_WIDTH - 4) + "..";
            }
            label += " ".repeat(NODE_WIDTH - 2 - CharWidth.of(label));
            write(area, buffer, node.x(), node.y(),
                    "+" + "-".repeat(NODE_WIDTH - 2) + "+", style);
            write(area, buffer, node.x(), node.y() + 1, "|" + label + "|", style);
            write(area, buffer, node.x(), node.y() + 2,
                    "+" + "-".repeat(NODE_WIDTH - 2) + "+", style);
        }
    }

    private void route(Edge edge, byte[][] strokes, byte[][] priorities,
            byte priority) {
        var source = edge.source();
        var target = edge.target();
        var startX = source.x() + NODE_WIDTH;
        var startY = source.y() + 1;
        var endX = target.x() - 1;
        var endY = target.y() + 1;
        var bendX = startX + COLUMN_GAP / 2 - 1;
        horizontal(startX, bendX, startY, strokes, priorities, priority);
        if (target.depth() == source.depth() + 1) {
            vertical(bendX, startY, endY, strokes, priorities, priority);
            horizontal(bendX, endX, endY, strokes, priorities, priority);
        } else {
            // Long edges cross intermediate layers between node rows.
            var laneY = target.y() - 1;
            var targetBend = target.x() - 2;
            vertical(bendX, startY, laneY, strokes, priorities, priority);
            horizontal(bendX, targetBend, laneY, strokes, priorities, priority);
            vertical(targetBend, laneY, endY, strokes, priorities, priority);
            horizontal(targetBend, endX, endY, strokes, priorities, priority);
        }
        stroke(endX, endY, (byte) 4, strokes, priorities, priority);
    }

    private void horizontal(int from, int to, int y, byte[][] strokes,
            byte[][] priorities, byte priority) {
        if (y < offsetY || y >= offsetY + viewportHeight) {
            return;
        }
        for (var x = Math.max(Math.min(from, to), offsetX);
                x <= Math.min(Math.max(from, to),
                        offsetX + viewportWidth - 1); x++) {
            stroke(x, y, (byte) 1, strokes, priorities, priority);
        }
    }

    private void vertical(int x, int from, int to, byte[][] strokes,
            byte[][] priorities, byte priority) {
        if (from == to || x < offsetX || x >= offsetX + viewportWidth) {
            return;
        }
        for (var y = Math.max(Math.min(from, to), offsetY);
                y <= Math.min(Math.max(from, to),
                        offsetY + viewportHeight - 1); y++) {
            stroke(x, y, (byte) 2, strokes, priorities, priority);
        }
    }

    private void stroke(int x, int y, byte kind, byte[][] strokes,
            byte[][] priorities, byte priority) {
        x -= offsetX;
        y -= offsetY;
        if (x >= 0 && x < viewportWidth && y >= 0 && y < viewportHeight) {
            strokes[y][x] |= kind;
            priorities[y][x] = (byte) Math.max(priorities[y][x], priority);
        }
    }

    private void write(Rect area, Buffer buffer, int x, int y,
            String text, Style style) {
        x -= offsetX;
        y -= offsetY;
        if (y < 0 || y >= viewportHeight
                || x >= viewportWidth || x + CharWidth.of(text) <= 0) {
            return;
        }
        for (var start = 0; start < text.length();) {
            var end = GraphemeClusters.clusterEnd(text, start);
            var cluster = text.substring(start, end);
            var cells = CharWidth.of(cluster);
            if (x >= 0 && x + cells <= viewportWidth) {
                buffer.setString(area.left() + x, area.top() + y, cluster, style);
            }
            x += cells;
            start = end;
        }
    }

    private static Set<String> reachable(String name,
            HashMap<String, List<String>> links) {
        var result = new HashSet<String>();
        var pending = new ArrayDeque<>(links.getOrDefault(name, List.of()));
        while (!pending.isEmpty()) {
            var next = pending.removeFirst();
            if (result.add(next)) {
                pending.addAll(links.getOrDefault(next, List.of()));
            }
        }
        return result;
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, Math.max(0, maximum)));
    }
}
