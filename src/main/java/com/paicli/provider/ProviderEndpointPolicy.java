package com.paicli.provider;

import java.net.URI;

public final class ProviderEndpointPolicy {
    public static final int MAX_ENDPOINT_CHARS = 512;

    private ProviderEndpointPolicy() { }

    public static URI validate(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ENDPOINT_CHARS
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("invalid provider endpoint");
        }
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("invalid provider endpoint"); }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("provider endpoint must be an HTTP(S) origin without credentials, query, or fragment");
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return URI.create(normalized);
    }

    public static String validateModelId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("invalid model id");
        }
        return value;
    }

    public static String validateEnvironmentName(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid API key environment variable name");
        }
        return value;
    }
}
