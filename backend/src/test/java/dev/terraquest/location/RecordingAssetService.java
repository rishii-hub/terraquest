package dev.terraquest.location;

import dev.terraquest.imagery.ImageAsset;
import dev.terraquest.imagery.ImageAssetService;
import dev.terraquest.imagery.Projection;
import dev.terraquest.storage.InMemoryStorageProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test double for {@code ImageAssetService} used by {@link LocationHarvesterTest}.
 *
 * <p>Subclasses the real service and overrides {@link #ingest} so nothing
 * touches storage or the network; the collaborators handed to {@code super} are
 * never used because {@code ingest} is fully overridden. Every ingest succeeds,
 * which is what lets the harvester tests assert how many locations were kept.
 */
class RecordingAssetService extends ImageAssetService {

    final List<UUID> ingested = new ArrayList<>();

    RecordingAssetService() {
        super(new InMemoryStorageProvider(), null, 30L * 1024 * 1024);
    }

    @Override
    public Optional<ImageAsset> ingest(UUID locationId, String sourceUrl, boolean panoramic) {
        ingested.add(locationId);
        return Optional.of(ImageAsset.builder()
                .locationId(locationId)
                .storageKey("test/" + locationId)
                .projection(panoramic ? Projection.EQUIRECTANGULAR : Projection.FLAT)
                .width(panoramic ? 4096 : 2048)
                .height(panoramic ? 2048 : 1365)
                .bytes(1_024L)
                .exifStripped(true)
                .build());
    }
}
