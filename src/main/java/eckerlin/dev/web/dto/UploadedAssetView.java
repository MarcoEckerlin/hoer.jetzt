package eckerlin.dev.web.dto;

public record UploadedAssetView(
        String assetId,
        String url,
        String contentType,
        String originalName,
        long sizeBytes
) {
}
