package dev.terraquest.game;

/** Mirrors the Postgres {@code game_status} enum (V1). */
public enum GameStatus {
    IN_PROGRESS,
    COMPLETED,
    ABANDONED
}
