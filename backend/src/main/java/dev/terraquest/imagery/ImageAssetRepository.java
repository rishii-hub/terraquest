package dev.terraquest.imagery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link ImageAsset}. {@code ImageAssetService.ingest} saves
 * through {@code save(..)} inherited from Spring Data.
 */
public interface ImageAssetRepository extends JpaRepository<ImageAsset, UUID> {
}
