package dev.terraquest.shared;

/**
 * Licence attribution as surfaced to the player on the result screen.
 *
 * <p>Lives in the shared kernel rather than in {@code game} on purpose: it is
 * produced in {@code imagery} (an {@code ImageAsset} attributes its source) and
 * consumed in {@code game} (it is a field of {@code GuessResult}). Putting the
 * type in either feature module would make the other depend back on it and
 * create a module cycle that ArchitectureTest forbids. A pure value type shared
 * by both is the leaf-kernel's whole reason to exist.
 *
 * @param contributor display name to credit
 * @param profileUrl  link to the contributor at the source, or null
 * @param licence     SPDX-style identifier, e.g. "CC-BY-SA-4.0"
 * @param sourceUrl   canonical link back to the image at its source
 */
public record Attribution(
        String contributor,
        String profileUrl,
        String licence,
        String sourceUrl
) {}
