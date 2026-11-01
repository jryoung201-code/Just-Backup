package dev.universalbackup.model;

import java.util.UUID;

public record PlayerSnapshot(UUID uuid, String playerName, long createdAt, byte[] compressedYaml, String checksum) {}
