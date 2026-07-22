package dev.terraquest.game;

import java.util.UUID;

/** Response to {@code POST /api/v1/games}: the new game and how many rounds it has. */
public record GameCreated(UUID gameId, int roundCount) {
}
