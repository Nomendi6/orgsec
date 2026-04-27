package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.storage.inmemory.StorageFeatureFlags;
import com.nomendi6.orgsec.storage.jwt.config.JwtStorageProperties;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that every property bound by an {@code @ConfigurationProperties} class
 * in the OrgSec library is documented in {@code docs/reference/properties.md},
 * and that the documentation does not list properties that no longer exist.
 *
 * <p>The test walks the public, settable fields of the five canonical configuration
 * classes (and their nested static config classes) and produces canonical
 * property paths. Map-typed fields are documented under a placeholder key
 * (for example {@code orgsec.business-roles.&lt;role&gt;.supported-fields});
 * see {@link #MAP_PLACEHOLDERS} for the enumerated keys per map.
 *
 * <p>This test acts as a single-source-of-truth gate: if you add or remove
 * an {@code @ConfigurationProperties} field, this test will fail until the
 * documentation is updated.
 */
class PropertiesDocumentationCoverageTest {

    private static final List<Class<?>> ROOT_PROPERTY_CLASSES = List.of(
        OrgsecProperties.class,
        BusinessRoleConfiguration.class,
        StorageFeatureFlags.class,
        RedisStorageProperties.class,
        JwtStorageProperties.class
    );

    /**
     * Map fields whose key set is enumerated in the docs rather than treated as a wildcard.
     * Each entry maps the *fully qualified* property path of the map field to the literal
     * keys we expect to be documented. Key {@code "<role>"} is a placeholder used for
     * fields whose key set is open-ended.
     */
    private static final Map<String, List<String>> MAP_PLACEHOLDERS = Map.of(
        "orgsec.business-roles", List.of("<role>.supported-fields", "<role>.rsql-fields"),
        "orgsec.storage.data-sources", List.of("person", "organization", "role", "privilege")
    );

    /**
     * Documented property paths that have no corresponding field in the source classes.
     * These exist because Spring reads them directly through {@code @ConditionalOnProperty}
     * (without a {@code @ConfigurationProperties} field) or because they are documented
     * for migration purposes.
     */
    private static final Set<String> DOCUMENTED_BUT_NOT_BOUND = Set.of(
        // Read by RedisStorageAutoConfiguration's @ConditionalOnProperty;
        // no field on RedisStorageProperties.
        "orgsec.storage.redis.enabled"
    );

    @Test
    void everyConfigurationPropertyIsDocumented() throws IOException {
        Set<String> sourcePaths = collectSourceProperties();
        Set<String> documented = extractDocumentedProperties(readDocs());

        TreeSet<String> missing = new TreeSet<>();
        for (String path : sourcePaths) {
            if (!documented.contains(path)) {
                missing.add(path);
            }
        }

        assertThat(missing)
            .as("Property paths declared in @ConfigurationProperties but not documented in docs/reference/properties.md")
            .isEmpty();
    }

    @Test
    void everyDocumentedPropertyHasACorrespondingField() throws IOException {
        Set<String> sourcePaths = collectSourceProperties();
        Set<String> documented = extractDocumentedProperties(readDocs());

        TreeSet<String> orphaned = new TreeSet<>();
        for (String path : documented) {
            if (sourcePaths.contains(path)) {
                continue;
            }
            if (DOCUMENTED_BUT_NOT_BOUND.contains(path)) {
                continue;
            }
            orphaned.add(path);
        }

        assertThat(orphaned)
            .as("Properties documented in docs/reference/properties.md that no @ConfigurationProperties field binds")
            .isEmpty();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private Set<String> collectSourceProperties() {
        Set<String> paths = new LinkedHashSet<>();
        for (Class<?> root : ROOT_PROPERTY_CLASSES) {
            ConfigurationProperties annotation = root.getAnnotation(ConfigurationProperties.class);
            if (annotation == null) {
                throw new IllegalStateException(root + " is not @ConfigurationProperties");
            }
            String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
            walk(root, prefix, paths);
        }
        return paths;
    }

    private void walk(Class<?> type, String prefix, Set<String> paths) {
        for (Field field : type.getDeclaredFields()) {
            if (!isCandidateField(field)) {
                continue;
            }
            String name = toKebabCase(field.getName());
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            Class<?> fieldType = field.getType();

            if (Map.class.isAssignableFrom(fieldType)) {
                List<String> placeholderSuffixes = MAP_PLACEHOLDERS.get(path);
                if (placeholderSuffixes == null) {
                    paths.add(path);
                    continue;
                }
                Class<?> valueType = mapValueType(field);
                for (String suffix : placeholderSuffixes) {
                    String mapped = path + "." + suffix;
                    if (valueType != null && hasNestedFields(valueType) && !suffix.contains(".")) {
                        walk(valueType, mapped, paths);
                    } else {
                        paths.add(mapped);
                    }
                }
                continue;
            }

            if (isLeafType(fieldType)) {
                paths.add(path);
                continue;
            }

            // Nested configuration: walk into it.
            walk(fieldType, path, paths);
        }
    }

    private boolean isCandidateField(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            return false;
        }
        if (Modifier.isTransient(modifiers)) {
            return false;
        }
        // The field must have a public setter so Spring binding can populate it.
        Class<?> declaring = field.getDeclaringClass();
        String setter = "set" + capitalize(field.getName());
        try {
            Method method = declaring.getMethod(setter, field.getType());
            return Modifier.isPublic(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private boolean isLeafType(Class<?> type) {
        if (type.isPrimitive()) return true;
        if (type.isEnum()) return true;
        if (Number.class.isAssignableFrom(type)) return true;
        if (CharSequence.class.isAssignableFrom(type)) return true;
        if (Boolean.class.equals(type)) return true;
        if (Character.class.equals(type)) return true;
        if (Collection.class.isAssignableFrom(type)) return true;
        if (Map.class.isAssignableFrom(type)) return true;
        return type.getName().startsWith("java.");
    }

    private boolean hasNestedFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).anyMatch(this::isCandidateField);
    }

    private Class<?> mapValueType(Field field) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type[] args = parameterized.getActualTypeArguments();
        if (args.length < 2) {
            return null;
        }
        Type valueType = args[1];
        if (valueType instanceof Class<?> klass) {
            return klass;
        }
        if (valueType instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    private static String toKebabCase(String camelCase) {
        StringBuilder sb = new StringBuilder(camelCase.length() + 5);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String readDocs() throws IOException {
        Path docs = locateDocs();
        return Files.readString(docs);
    }

    private Path locateDocs() {
        Path candidate = Path.of("..", "docs", "reference", "properties.md").toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = Path.of("docs", "reference", "properties.md").toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        throw new IllegalStateException(
            "docs/reference/properties.md not found relative to module working directory; tried: "
                + Path.of("..", "docs", "reference", "properties.md").toAbsolutePath().normalize()
                + " and "
                + Path.of("docs", "reference", "properties.md").toAbsolutePath().normalize()
        );
    }

    /**
     * Extract every backticked property path that starts with one of the known prefixes.
     *
     * <p>Documentation tables have the shape
     * {@code | `property.path` | `boolean` | `default` | description | See |}.
     * Properties are always in the *first* table cell &mdash; types and defaults are
     * in subsequent cells. We therefore parse only the first cell of each row
     * (and headings, which carry the active prefix).
     */
    private Set<String> extractDocumentedProperties(String docs) {
        Set<String> found = new TreeSet<>();
        String[] lines = docs.split("\n");
        String activePrefix = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("##")) {
                activePrefix = parsePrefixFromHeading(trimmed);
                continue;
            }
            if (!trimmed.startsWith("|")) {
                continue;
            }
            // Skip the table header / separator rows.
            if (trimmed.contains("---") || trimmed.toLowerCase().contains("| property")) {
                continue;
            }
            // First cell content lies between the first and second pipe.
            int firstPipe = trimmed.indexOf('|');
            int secondPipe = trimmed.indexOf('|', firstPipe + 1);
            if (firstPipe < 0 || secondPipe < 0) continue;
            String firstCell = trimmed.substring(firstPipe + 1, secondPipe).trim();

            int btStart = firstCell.indexOf('`');
            int btEnd = firstCell.indexOf('`', btStart + 1);
            if (btStart < 0 || btEnd < 0) continue;
            String token = firstCell.substring(btStart + 1, btEnd);

            String full;
            if (token.startsWith("orgsec.") || token.equals("orgsec")) {
                full = token;
            } else if (activePrefix != null && token.matches("[a-z0-9.<>_-]+")) {
                full = activePrefix + "." + token;
            } else {
                continue;
            }
            if (full.startsWith("orgsec")) {
                found.add(full);
            }
        }
        return found;
    }

    private String parsePrefixFromHeading(String headingLine) {
        // Headings may carry several backticked tokens (class name + prefix).
        // We pick the first token that starts with "orgsec".
        int cursor = 0;
        while (cursor < headingLine.length()) {
            int first = headingLine.indexOf('`', cursor);
            if (first < 0) return null;
            int second = headingLine.indexOf('`', first + 1);
            if (second < 0) return null;
            String token = headingLine.substring(first + 1, second);
            if (token.startsWith("orgsec")) {
                if (token.endsWith(".*")) {
                    token = token.substring(0, token.length() - 2);
                }
                return token;
            }
            cursor = second + 1;
        }
        return null;
    }
}
