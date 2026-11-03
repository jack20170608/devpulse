package io.github.devpulse.poc.template;

import freemarker.core.PlainTextOutputFormat;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.SimpleObjectWrapper;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class MarkdownTemplateRenderer {
    public String render(Path template, Map<String, ?> parameters)
            throws IOException {
        var configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setOutputFormat(PlainTextOutputFormat.INSTANCE);
        configuration.setObjectWrapper(
                new SimpleObjectWrapper(Configuration.VERSION_2_3_34));
        configuration.setTemplateExceptionHandler(
                TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        configuration.setFallbackOnNullLoopVariable(false);
        configuration.setNewBuiltinClassResolver(
                TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        configuration.setAPIBuiltinEnabled(false);
        configuration.setNumberFormat("computer");
        configuration.setBooleanFormat("c");
        try (var reader = Files.newBufferedReader(template,
                StandardCharsets.UTF_8)) {
            var compiled = new Template(template.getFileName().toString(),
                    reader, configuration);
            var result = new StringWriter();
            compiled.process(parameters, result);
            return result.toString();
        } catch (TemplateException exception) {
            throw new IOException("Template rendering failed: "
                    + exception.getMessageWithoutStackTop(), exception);
        }
    }
}
