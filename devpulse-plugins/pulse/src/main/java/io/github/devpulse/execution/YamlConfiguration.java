package io.github.devpulse.execution;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigOriginFactory;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.MappingEndEvent;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.events.SequenceEndEvent;
import org.snakeyaml.engine.v2.events.SequenceStartEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;
import org.snakeyaml.engine.v2.schema.CoreSchema;

final class YamlConfiguration {
    private static final int SIZE_LIMIT = 1024 * 1024;
    private static final CoreSchema SCHEMA = new CoreSchema() {
        @Override
        public ScalarResolver getScalarResolver() {
            // Keep merge keys visible for validation instead of composing them away.
            return new CoreScalarResolver(false);
        }
    };

    private YamlConfiguration() {}

    static Config load(Path file) throws IOException {
        if (Files.size(file) > SIZE_LIMIT) {
            throw new IOException(file + ": YAML must not exceed 1 MiB.");
        }
        var source = Files.readString(file, StandardCharsets.UTF_8);
        var settings = LoadSettings.builder().setLabel(file.toString())
                .setSchema(SCHEMA).setCodePointLimit(SIZE_LIMIT)
                .setMaxAliasesForCollections(0).build();
        try {
            var depth = 0;
            for (var event : new Parse(settings).parseString(source)) {
                if (event instanceof ScalarEvent scalar
                        && scalar.getTag().filter(Tag.MERGE.getValue()::equals).isPresent()) {
                    throw new IOException(file + ": YAML merge keys are not supported.");
                }
                if (event instanceof AliasEvent) {
                    throw new IOException(file + ": YAML aliases are not supported.");
                }
                if (event instanceof MappingStartEvent
                        || event instanceof SequenceStartEvent) {
                    if (++depth > 64) {
                        throw new IOException(file + ": YAML nesting exceeds 64 levels.");
                    }
                } else if (event instanceof MappingEndEvent
                        || event instanceof SequenceEndEvent) {
                    depth--;
                }
            }
            var node = new Compose(settings).composeString(source)
                    .orElseThrow(() -> new IOException(file + ": Empty YAML configuration."));
            if (!(convert(node, file) instanceof ConfigObject object)) {
                throw invalid(node, file, "The YAML document must be a mapping.");
            }
            return object.toConfig();
        } catch (YamlEngineException exception) {
            throw new IOException("Invalid YAML in " + file + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static ConfigValue convert(Node node, Path file) {
        if (node.getAnchor().isPresent()) {
            throw invalid(node, file, "YAML anchors are not supported.");
        }
        var origin = origin(node, file);
        if (node instanceof MappingNode mapping && node.getTag().equals(Tag.MAP)) {
            var values = new LinkedHashMap<String, ConfigValue>();
            for (var tuple : mapping.getValue()) {
                var key = tuple.getKeyNode();
                if (!(key instanceof ScalarNode scalar)
                        || !key.getTag().equals(Tag.STR)) {
                    throw invalid(key, file, "Mapping keys must be strings.");
                }
                if (key.getAnchor().isPresent() || scalar.getValue().equals("<<")) {
                    throw invalid(key, file, "YAML anchors and merge keys are not supported.");
                }
                var name = scalar.getValue();
                if (values.containsKey(name)) {
                    throw invalid(key, file, "Duplicate YAML key: " + name);
                }
                values.put(name, convert(tuple.getValueNode(), file));
            }
            return ConfigValueFactory.fromMap(values).withOrigin(origin);
        }
        if (node instanceof SequenceNode sequence && node.getTag().equals(Tag.SEQ)) {
            var values = new ArrayList<ConfigValue>();
            for (var value : sequence.getValue()) {
                values.add(convert(value, file));
            }
            return ConfigValueFactory.fromIterable(values).withOrigin(origin);
        }
        if (node instanceof ScalarNode scalar) {
            if (node.getTag().equals(Tag.STR)) {
                return ConfigValueFactory.fromAnyRef(scalar.getValue()).withOrigin(origin);
            }
            if (List.of(Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT).contains(node.getTag())) {
                try {
                    var value = SCHEMA.getSchemaTagConstructors().get(node.getTag())
                            .construct(node);
                    if (value instanceof Double number && !Double.isFinite(number)) {
                        throw invalid(node, file, "Numbers must be finite.");
                    }
                    return ConfigValueFactory.fromAnyRef(value).withOrigin(origin);
                } catch (NumberFormatException exception) {
                    throw invalid(node, file, "Invalid numeric scalar.");
                }
            }
        }
        throw invalid(node, file, "Unsupported YAML tag: " + node.getTag());
    }

    private static ConfigOrigin origin(Node node, Path file) {
        return ConfigOriginFactory.newFile(file.toString()).withLineNumber(
                node.getStartMark().map(mark -> mark.getLine() + 1).orElse(1));
    }

    private static ConfigException.BadValue invalid(Node node, Path file,
            String message) {
        return new ConfigException.BadValue(origin(node, file), "YAML", message);
    }
}
