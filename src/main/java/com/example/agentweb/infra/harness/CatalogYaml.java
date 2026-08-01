package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.harness.HarnessCatalogException;

import java.util.List;
import java.util.Map;

/**
 * Harness 部署模板在迁移窗口内使用的 YAML 兼容入口。
 *
 * @author alex
 * @since 2026-08-01
 */
final class CatalogYaml {

    private final com.example.agentweb.infra.capability.CatalogYaml delegate;

    private CatalogYaml(com.example.agentweb.infra.capability.CatalogYaml delegate) {
        this.delegate = delegate;
    }

    static CatalogYaml parse(byte[] bytes, String source) {
        try {
            return new CatalogYaml(com.example.agentweb.infra.capability.CatalogYaml.parse(bytes, source));
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        }
    }

    String requiredString(String key) {
        try {
            return delegate.requiredString(key);
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        }
    }

    String optionalString(String key) {
        return delegate.optionalString(key);
    }

    Map<String, Object> requiredMap(String key) {
        try {
            return delegate.requiredMap(key);
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        }
    }

    List<String> stringList(String key) {
        try {
            return delegate.stringList(key);
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        }
    }

    List<Map<String, Object>> mapList(String key) {
        try {
            return delegate.mapList(key);
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        }
    }

    static String requiredString(Map<String, Object> map, String key) {
        try {
            return com.example.agentweb.infra.capability.CatalogYaml.requiredString(map, key);
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        }
    }

    private static HarnessCatalogException translate(CapabilityCatalogException ex) {
        return new HarnessCatalogException(ex.getCode(), ex.getMessage(), ex);
    }
}
