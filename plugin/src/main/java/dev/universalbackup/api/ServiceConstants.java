package dev.universalbackup.api;

public final class ServiceConstants {
    private ServiceConstants() {}

    // This is intentionally compiled into the plugin instead of exposed in config.yml.
    // Replace with the HTTPS URL of the owner's hosted backup API before production builds.
    public static final String API_BASE_URL = "https://REPLACE-WITH-YOUR-BACKUP-API.example";
    public static final int CHUNK_SIZE = 4 * 1024 * 1024;
}
