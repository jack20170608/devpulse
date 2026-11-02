package io.github.devpulse.jira;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigIncluderClasspath;
import com.typesafe.config.ConfigIncluderFile;
import com.typesafe.config.ConfigIncluderURL;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValueType;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public record JiraConfiguration(String url, String mode, String email,
        int apiVersion) {
    public static JiraConfiguration load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Jira configuration not found: " + file
                    + ". Create .jira.conf or specify --config FILE.");
        }
        try {
            var config = ConfigFactory.parseString(
                    Files.readString(file, StandardCharsets.UTF_8),
                    ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF)
                            .setIncluder(new NoIncludes()))
                    .resolve(ConfigResolveOptions.noSystem());
            if (!Set.of("url", "mode", "email", "apiVersion")
                    .containsAll(config.root().keySet())) {
                throw new IOException("Jira configuration supports only "
                        + "url, mode, email, and apiVersion; "
                        + "use JIRA_API_TOKEN "
                        + "for authentication.");
            }
            var mode = required(config, "mode");
            if (!mode.equals("cloud") && !mode.equals("dc")) {
                throw new IOException("Jira configuration mode "
                        + "must be cloud or dc.");
            }
            return new JiraConfiguration(required(config, "url"), mode,
                    mode.equals("cloud") || config.hasPathOrNull("email")
                            ? required(config, "email") : null,
                    apiVersion(config, mode));
        } catch (ConfigException exception) {
            // Parser messages may echo file contents or substituted values.
            throw new IOException("Invalid Jira connection configuration; "
                    + "check HOCON syntax and "
                    + "url/mode/email/apiVersion fields.");
        }
    }

    private static int apiVersion(Config config, String mode)
            throws IOException {
        if (!config.hasPathOrNull("apiVersion")) {
            return mode.equals("cloud") ? 3 : 2;
        }
        if (!config.hasPath("apiVersion")
                || config.getValue("apiVersion").valueType()
                != ConfigValueType.NUMBER) {
            throw new IOException("Jira configuration apiVersion "
                    + "must be the number 2 or 3.");
        }
        var value = config.getNumber("apiVersion").doubleValue();
        if (value != 2 && value != 3) {
            throw new IOException("Jira configuration apiVersion "
                    + "must be the number 2 or 3.");
        }
        return (int) value;
    }

    private static String required(Config config, String field)
            throws IOException {
        if (!config.hasPath(field)
                || config.getValue(field).valueType()
                != ConfigValueType.STRING) {
            throw invalid(field);
        }
        var value = config.getString(field);
        if (value.isBlank()
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field);
        }
        return value;
    }

    private static IOException invalid(String field) {
        return new IOException("Jira configuration field " + field
                + " must be a nonblank string without control characters.");
    }

    private static final class NoIncludes implements ConfigIncluder,
            ConfigIncluderFile, ConfigIncluderURL, ConfigIncluderClasspath {
        @Override
        public ConfigIncluder withFallback(ConfigIncluder fallback) {
            return this;
        }

        @Override
        public ConfigObject include(ConfigIncludeContext context,
                String name) {
            throw denied();
        }

        @Override
        public ConfigObject includeFile(ConfigIncludeContext context,
                File file) {
            throw denied();
        }

        @Override
        public ConfigObject includeURL(ConfigIncludeContext context,
                URL url) {
            throw denied();
        }

        @Override
        public ConfigObject includeResources(ConfigIncludeContext context,
                String resource) {
            throw denied();
        }

        private static ConfigException denied() {
            return new ConfigException.Generic(
                    "Jira connection configuration cannot include resources.");
        }
    }
}
