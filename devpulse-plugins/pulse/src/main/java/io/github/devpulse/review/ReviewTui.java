package io.github.devpulse.review;

import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.error.RenderErrorHandlers;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.devpulse.execution.PulseConfiguration;
import io.github.devpulse.execution.PulsePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public final class ReviewTui {
    private static final Style ACCENT = Style.create().cyan().bold();
    private final String target;
    private final PulseConfiguration configuration;
    private final PulsePlan plan;
    private final int jobs;
    private final ListState selection = new ListState();
    private final DagWidget dag;
    private final String[] taskLabels;
    private boolean graphView = true;
    private int scroll;
    private int maxScroll;
    private int pageSize = 1;
    private IOException renderingFailure;

    private ReviewTui(String target, PulseConfiguration configuration,
            PulsePlan plan, int jobs) {
        this.target = target;
        this.configuration = configuration;
        this.plan = plan;
        this.jobs = jobs;
        dag = new DagWidget(plan);
        selection.selectFirst();
        taskLabels = new String[plan.tasks().size()];
        for (var index = 0; index < plan.tasks().size(); index++) {
            var task = plan.tasks().get(index);
            taskLabels[index] = "%2d  L%d  %s".formatted(index + 1,
                    dag.depth(task.name()),
                    display(task.name()));
        }
    }

    public static boolean show(String target,
            PulseConfiguration configuration, PulsePlan plan, int jobs)
            throws IOException, InterruptedException {
        try (var terminal = TerminalBuilder.builder()
                .system(true)
                .systemOutput(TerminalBuilder.SystemOutput.SysOut)
                .encoding(StandardCharsets.UTF_8)
                .dumb(true)
                .build()) {
            if (terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
                return false;
            }
            new ReviewTui(target, configuration, plan, jobs).run(terminal);
            return true;
        }
    }

    private void run(Terminal terminal)
            throws IOException, InterruptedException {
        var config = TuiConfig.builder()
                .backend(new JLineBackend(terminal))
                .noTick()
                .errorHandler((error, context) -> {
                    renderingFailure = new IOException(
                            "Could not render the review interface: " + error);
                    return RenderErrorHandlers.logAndQuit(System.err)
                            .handle(error, context);
                })
                .build();
        try (var runner = TuiRunner.create(config)) {
            runner.run(this::handle, this::render);
            if (renderingFailure != null) {
                throw renderingFailure;
            }
        } catch (IOException | InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not open the review interface.",
                    exception);
        }
    }

    private boolean handle(Event event, TuiRunner runner) {
        if (!(event instanceof KeyEvent key)) {
            return false;
        }
        if (key.isQuit() || key.isCtrlC()) {
            runner.quit();
            return false;
        }
        var previous = selection.selected();
        if (key.isKey(KeyCode.UP)) {
            selection.selectPrevious();
        } else if (key.isKey(KeyCode.DOWN)) {
            selection.selectNext(plan.tasks().size());
        } else if (key.isKey(KeyCode.HOME)) {
            selection.selectFirst();
        } else if (key.isKey(KeyCode.END)) {
            selection.selectLast(plan.tasks().size());
        } else if (key.isKey(KeyCode.PAGE_UP)) {
            scroll = Math.max(0, scroll - pageSize);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            scroll = Math.min(maxScroll, scroll + pageSize);
        } else if (key.isKey(KeyCode.TAB)) {
            graphView = !graphView;
            dag.center();
        } else if (key.isKey(KeyCode.LEFT)) {
            dag.pan(-8, 0);
        } else if (key.isKey(KeyCode.RIGHT)) {
            dag.pan(8, 0);
        } else if (key.isChar('w')) {
            dag.pan(0, -4);
        } else if (key.isChar('s')) {
            dag.pan(0, 4);
        } else if (key.isChar('r')) {
            dag.center();
        } else {
            return false;
        }
        if (!previous.equals(selection.selected())) {
            scroll = 0;
            dag.select(plan.tasks().get(selection.selected()).name());
        }
        return true;
    }

    private void render(Frame frame) {
        if (frame.width() < 60 || frame.height() < 16) {
            frame.renderWidget(Paragraph.from(
                    "Enlarge the terminal to at least 60 x 16. Press q to quit."),
                    frame.area());
            return;
        }
        var rows = Layout.vertical().constraints(Constraint.length(6),
                Constraint.fill(), Constraint.length(3)).split(frame.area());
        frame.renderWidget(Paragraph.builder()
                .block(block("devpulse / review"))
                .text(Text.from(
                        Line.styled("Target: " + display(target)
                                + "    Tasks: " + plan.tasks().size()
                                + "    Parallelism: " + jobs, ACCENT),
                        Line.from("Project: "
                                + display(configuration.directory().toString())),
                        Line.from("L = dependency depth, not an execution batch"),
                        Line.from("Yellow: selected  Green: prerequisites"
                                + "  Magenta: unlocks")))
                .build(), rows.get(0));
        var columns = Layout.horizontal().constraints(
                Constraint.percentage(65), Constraint.fill()).split(rows.get(1));
        if (graphView) {
            var graphBlock = block("DAG / left to right");
            frame.renderWidget(graphBlock, columns.get(0));
            frame.renderWidget(dag, graphBlock.inner(columns.get(0)));
        } else {
            frame.renderStatefulWidget(ListWidget.builder()
                    .items(taskLabels)
                    .block(block("Tasks / topological order"))
                    .highlightSymbol("> ")
                    .highlightStyle(Style.create().onBlue().white().bold())
                    .build(), columns.get(0), selection);
        }

        var task = plan.tasks().get(selection.selected());
        var detailsBlock = block("Task: " + display(task.name()));
        var area = detailsBlock.inner(columns.get(1));
        var details = new ArrayList<Line>();
        details.add(Line.styled(
                "Prerequisites (success / daemon readiness):", ACCENT));
        if (task.needs().isEmpty()) {
            details.add(Line.from("  (none / ready initially)"));
        } else {
            task.needs().forEach(name -> details.add(Line.from(
                    "  " + display(name) + " --> " + display(task.name()))));
        }
        details.add(Line.empty());
        details.add(Line.styled("Unlocks (within this plan):", ACCENT));
        var dependents = plan.tasks().stream()
                .filter(next -> next.needs().contains(task.name())).toList();
        if (dependents.isEmpty()) {
            details.add(Line.from("  (none / final task)"));
        } else {
            dependents.forEach(next -> details.add(Line.from(
                    "  " + display(task.name()) + " --> "
                            + display(next.name()))));
        }
        details.add(Line.empty());
        details.add(Line.styled("Command / Bash:", ACCENT));
        display(task.command()).lines()
                .forEach(line -> details.add(Line.from(line)));
        if (task.daemon() != null) {
            details.add(Line.empty());
            details.add(Line.styled("Daemon lifecycle:", ACCENT));
            TaskDetails.daemonDetails(task, plan.daemonOptions().get(task.name()))
                    .forEach(value -> display(value).lines()
                    .forEach(line -> details.add(Line.from(line))));
        }
        var wrapped = wrap(details, Math.max(1, area.width()));
        pageSize = Math.max(1, area.height());
        maxScroll = Math.max(0, wrapped.size() - pageSize);
        scroll = Math.min(scroll, maxScroll);
        frame.renderWidget(Paragraph.builder().block(detailsBlock)
                .text(Text.from(wrapped)).scroll(scroll).build(), columns.get(1));
        frame.renderWidget(Paragraph.from(Text.from(
                Line.from("Up/Down: task  Left/Right: pan X  w/s: pan Y"),
                Line.from("PgUp/PgDn: details  Tab: graph/list  r: center  q: quit"),
                Line.from("Static review only. No commands executed."
                        + (graphView ? "  DAG: " + dag.position()
                                : "  Details: " + (scroll + 1) + "/"
                                        + Math.max(1, wrapped.size()))))),
                rows.get(2));
    }

    private static Block block(String title) {
        return Block.builder().title(title).borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.CYAN).build();
    }

    private static List<Line> wrap(List<Line> lines, int width) {
        var result = new ArrayList<Line>();
        for (var line : lines) {
            var remaining = line.rawContent();
            if (remaining.isEmpty()) {
                result.add(line);
                continue;
            }
            var style = line.spans().getFirst().style();
            while (!remaining.isEmpty()) {
                var part = CharWidth.substringByWidth(remaining, width);
                if (part.isEmpty()) {
                    result.add(Line.styled("?", style));
                    remaining = remaining.substring(
                            remaining.offsetByCodePoints(0, 1));
                } else {
                    result.add(Line.styled(part, style));
                    remaining = remaining.substring(part.length());
                }
            }
        }
        return result;
    }

    private static String display(String value) {
        var result = new StringBuilder();
        value.codePoints().forEach(code -> {
            if (code == '\t') {
                result.append("    ");
            } else if (code != '\n' && Character.isISOControl(code)) {
                result.append("\\u%04x".formatted(code));
            } else {
                result.appendCodePoint(code);
            }
        });
        return result.toString();
    }
}
