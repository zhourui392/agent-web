package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Capability Catalog 的安全 YAML 技术解析器。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class CatalogYaml {

    private final Map<String, Object> values;

    private CatalogYaml(Map<String, Object> values) {
        this.values = values;
    }

    public static CatalogYaml parse(byte[] bytes, String source) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new String(bytes, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map)) {
                throw failure("CATALOG_MANIFEST_INVALID", "manifest must be a YAML mapping: " + source);
            }
            return new CatalogYaml(stringKeyMap((Map<?, ?>) loaded, source));
        } catch (CapabilityCatalogException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "cannot parse catalog manifest: " + source, ex);
        }
    }

    public String requiredString(String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw failure("CATALOG_MANIFEST_INVALID", "manifest field must not be blank: " + key);
        }
        return String.valueOf(value).trim();
    }

    public String optionalString(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public boolean requiredBoolean(String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean)) {
            throw failure("CATALOG_MANIFEST_INVALID",
                    "manifest field must be a boolean: " + key);
        }
        return ((Boolean) value).booleanValue();
    }

    public Map<String, Object> requiredMap(String key) {
        Object value = values.get(key);
        if (!(value instanceof Map)) {
            throw failure("CATALOG_MANIFEST_INVALID", "manifest field must be a mapping: " + key);
        }
        return stringKeyMap((Map<?, ?>) value, key);
    }

    public List<String> stringList(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw failure("CATALOG_MANIFEST_INVALID", "manifest field must be a list: " + key);
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (item == null || String.valueOf(item).trim().isEmpty()) {
                throw failure("CATALOG_MANIFEST_INVALID", "manifest list contains blank value: " + key);
            }
            result.add(String.valueOf(item).trim());
        }
        return result;
    }

    public List<String> requiredExclusiveStringList(
            String publicKey, String legacyKey) {
        boolean publicDeclared = values.containsKey(publicKey);
        boolean legacyDeclared = values.containsKey(legacyKey);
        if (publicDeclared == legacyDeclared) {
            throw failure("CATALOG_MANIFEST_INVALID",
                    "manifest must declare exactly one of "
                            + publicKey + " or " + legacyKey);
        }
        String selectedKey = publicDeclared ? publicKey : legacyKey;
        List<String> result = stringList(selectedKey);
        if (result.isEmpty()) {
            throw failure("CATALOG_MANIFEST_INVALID",
                    "manifest list must not be empty: " + selectedKey);
        }
        return result;
    }

    public List<Map<String, Object>> mapList(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw failure("CATALOG_MANIFEST_INVALID", "manifest field must be a list: " + key);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map)) {
                throw failure("CATALOG_MANIFEST_INVALID", "manifest list must contain mappings: " + key);
            }
            result.add(stringKeyMap((Map<?, ?>) item, key));
        }
        return result;
    }

    public void requireOnlyKeys(String... allowedKeys) {
        Set<String> allowed = new HashSet<String>(Arrays.asList(allowedKeys));
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw failure("CATALOG_MANIFEST_INVALID",
                        "manifest contains unsupported field: " + key);
            }
        }
    }

    public static String requiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw failure("CATALOG_MANIFEST_INVALID", "manifest field must not be blank: " + key);
        }
        return String.valueOf(value).trim();
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> input, String source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                throw failure("CATALOG_MANIFEST_INVALID", "manifest contains null key: " + source);
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static CapabilityCatalogException failure(String code, String message) {
        return new CapabilityCatalogException(code, message);
    }
}
