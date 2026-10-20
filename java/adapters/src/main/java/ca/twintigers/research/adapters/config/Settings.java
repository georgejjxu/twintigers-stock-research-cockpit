package ca.twintigers.research.adapters.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Runtime settings: environment variables, then the KEY=VALUE secrets file named by RESEARCH_ENV_FILE
 * (KEY=VALUE lines), then the rules configuration (properties file named by
 * RESEARCH_RULES_FILE, default {@code java/config/rules.properties} under the project root).
 * Values are never logged.
 */
public final class Settings {

    private final Map<String, String> values;
    private final Map<String, String> rules;

    public Settings(Map<String, String> values, Map<String, String> rules) {
        this.values = Map.copyOf(values);
        this.rules = Map.copyOf(rules);
    }

    public static Settings load() {
        Map<String, String> v = new LinkedHashMap<>();
        // Scheduled tasks cannot set environment variables, so the two file paths may also come as -D system properties.
        for (String k : new String[] {"RESEARCH_ENV_FILE", "RESEARCH_RULES_FILE"}) {
            String p = System.getProperty(k);
            if (p != null) {
                v.put(k, p);
            }
        }
        String envFile = System.getenv().getOrDefault("RESEARCH_ENV_FILE", v.get("RESEARCH_ENV_FILE"));
        if (envFile != null) {
            v.putAll(readEnvFile(Path.of(envFile)));
        }
        System.getenv().forEach(v::put); // the environment wins over the file
        Map<String, String> rules = new LinkedHashMap<>();
        String rulesFile = v.getOrDefault("RESEARCH_RULES_FILE", "");
        Path rp = rulesFile.isBlank() ? null : Path.of(rulesFile);
        if (rp != null && Files.isRegularFile(rp)) {
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(rp, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read rules file " + rp + ": " + e.getMessage(), e);
            }
            p.forEach((k, val) -> rules.put(k.toString(), val.toString()));
        }
        return new Settings(v, rules);
    }

    static Map<String, String> readEnvFile(Path p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(p)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.startsWith("﻿")) {
                    s = s.substring(1);
                }
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                    continue;
                }
                String key = s.substring(0, s.indexOf('=')).strip();
                String val = s.substring(s.indexOf('=') + 1).strip();
                if (val.length() >= 2 && (val.charAt(0) == '"' || val.charAt(0) == '\'') && val.charAt(val.length() - 1) == val.charAt(0)) {
                    val = val.substring(1, val.length() - 1);
                }
                out.put(key, val);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + p + ": " + e.getMessage(), e);
        }
        return out;
    }

    public String get(String key, String def) {
        String v = values.get(key);
        return v == null || v.isBlank() ? def : v;
    }

    public String get(String key) {
        return get(key, null);
    }

    public Map<String, String> rules() {
        return rules;
    }

    /** Cache directory for SEC files and the database (in Docker: /data, set by RESEARCH_CACHE_DIR). */
    public Path cacheDir() {
        return Path.of(get("RESEARCH_CACHE_DIR", Path.of(System.getProperty("user.home"), ".cache", "research-tool").toString()));
    }
}
