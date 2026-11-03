package io.github.devpulse.poc.template;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "poc-template", mixinStandardHelpOptions = true,
        description = "Render a local FreeMarker template into a new Markdown file.")
public final class TemplateCommand implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Option(names = {"-t", "--template"}, required = true, paramLabel = "FILE",
            description = "UTF-8 FreeMarker template, e.g. issue.md.ftl.")
    private Path template;

    @Option(names = "--params", paramLabel = "FILE",
            description = "Optional HOCON parameter file.")
    private Path parameterFile;

    @Option(names = {"-D", "--param"}, paramLabel = "KEY=HOCON",
            description = "Repeat to override parameters; supports dotted keys.")
    private List<String> parameters = new ArrayList<>();

    @Option(names = {"-o", "--output"}, required = true, paramLabel = "FILE",
            description = "New UTF-8 Markdown file; existing files are never overwritten.")
    private Path output;

    public static void main(String[] args) {
        var command = new CommandLine(new TemplateCommand())
                .setOut(new PrintWriter(System.out, true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        System.exit(command.execute(args));
    }

    @Override
    public Integer call() {
        try {
            var values = parameters().resolve(ConfigResolveOptions.noSystem());
            var rendered = new MarkdownTemplateRenderer()
                    .render(template, values.root().unwrapped());
            Files.writeString(output, rendered, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            spec.commandLine().getOut().println("Created " + output + ".");
            return 0;
        } catch (FileAlreadyExistsException exception) {
            return fail("Output already exists: " + output);
        } catch (ConfigException exception) {
            return fail("Invalid HOCON parameters; check values, paths, and substitutions.");
        } catch (IOException exception) {
            return fail(exception.getMessage());
        }
    }

    private Config parameters() throws IOException {
        var options = ConfigParseOptions.defaults()
                .setSyntax(ConfigSyntax.CONF).setAllowMissing(false);
        var result = parameterFile == null ? ConfigFactory.empty()
                : ConfigFactory.parseFile(parameterFile.toFile(), options);
        for (var parameter : parameters) {
            var separator = parameter.indexOf('=');
            if (separator <= 0 || parameter.substring(0, separator).isBlank()) {
                throw new IOException("Each --param must use KEY=HOCON syntax.");
            }
            var key = parameter.substring(0, separator).trim();
            var parsed = ConfigFactory.parseString(
                    "value = " + parameter.substring(separator + 1), options)
                    .root();
            var value = parsed.get("value");
            if (value == null || parsed.size() != 1) {
                throw new IOException("Each --param must contain one HOCON value.");
            }
            result = result.withValue(key, value);
        }
        return result;
    }

    private int fail(String message) {
        spec.commandLine().getErr().println("poc-template: " + message);
        return 1;
    }
}
