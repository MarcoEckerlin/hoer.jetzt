package eckerlin.dev.services;

import eckerlin.dev.utils.Config;
import eckerlin.dev.utils.DB;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class UploadedAssetService {

    private static final long MAX_FILE_SIZE_BYTES = 3L * 1024L * 1024L;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
    );

    private final int botId = Config.config.optInt("bot_id", 1);
    private final AppConfigService configService;

    public UploadedAssetService(AppConfigService configService) {
        this.configService = configService;
    }

    public StoredAsset store(MultipartFile file, String createdByUserId) throws SQLException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Bitte waehle zuerst eine Bilddatei aus.");
        }
        if (!DB.isAvailable()) {
            throw new IllegalStateException("Die Bildspeicherung ist aktuell nicht verfuegbar.");
        }

        long size = file.getSize();
        if (size <= 0L) {
            throw new IllegalArgumentException("Die hochgeladene Datei ist leer.");
        }
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Bilder duerfen maximal 3 MB gross sein.");
        }

        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Die Bilddatei konnte nicht gelesen werden.");
        }

        String assetId = UUID.randomUUID().toString().replace("-", "");
        String sql = """
                INSERT INTO uploaded_assets (
                    asset_id,
                    bot_id,
                    created_by_user_id,
                    original_name,
                    content_type,
                    base64_data,
                    size_bytes
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, assetId);
            statement.setInt(2, botId);
            statement.setString(3, blank(createdByUserId));
            statement.setString(4, sanitizeFileName(file.getOriginalFilename()));
            statement.setString(5, contentType);
            statement.setString(6, Base64.getEncoder().encodeToString(bytes));
            statement.setLong(7, size);
            statement.executeUpdate();
        }

        return new StoredAsset(
                assetId,
                buildPublicUrl(assetId),
                contentType,
                sanitizeFileName(file.getOriginalFilename()),
                bytes,
                size
        );
    }

    public Optional<StoredAsset> find(String assetId) {
        if (assetId == null || assetId.isBlank() || !DB.isAvailable()) {
            return Optional.empty();
        }

        String sql = """
                SELECT asset_id, original_name, content_type, base64_data, size_bytes
                FROM uploaded_assets
                WHERE asset_id = ? AND bot_id = ?
                LIMIT 1
                """;

        try (Connection connection = DB.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, assetId.trim());
            statement.setInt(2, botId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                String base64Data = resultSet.getString("base64_data");
                byte[] bytes = base64Data == null || base64Data.isBlank()
                        ? new byte[0]
                        : Base64.getDecoder().decode(base64Data);

                return Optional.of(new StoredAsset(
                        resultSet.getString("asset_id"),
                        buildPublicUrl(resultSet.getString("asset_id")),
                        resultSet.getString("content_type"),
                        resultSet.getString("original_name"),
                        bytes,
                        resultSet.getLong("size_bytes")
                ));
            }
        } catch (SQLException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String buildPublicUrl(String assetId) {
        String normalizedId = assetId == null ? "" : assetId.trim();
        if (normalizedId.isBlank()) {
            return "";
        }

        String baseUrl = configService.getWebBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "/media/" + normalizedId;
        }

        String trimmedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return trimmedBase + "/media/" + normalizedId;
    }

    private String normalizeContentType(String rawContentType, String originalName) {
        String normalized = rawContentType == null ? "" : rawContentType.trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            normalized = "image/jpeg";
        }

        if (!normalized.isBlank() && ALLOWED_TYPES.contains(normalized)) {
            return normalized;
        }

        String extension = extractExtension(originalName);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> throw new IllegalArgumentException("Erlaubt sind nur PNG, JPG, GIF oder WebP.");
        };
    }

    private String extractExtension(String name) {
        String normalized = sanitizeFileName(name).toLowerCase(Locale.ROOT);
        int index = normalized.lastIndexOf('.');
        return index >= 0 && index + 1 < normalized.length() ? normalized.substring(index + 1) : "";
    }

    private String sanitizeFileName(String fileName) {
        String raw = fileName == null ? "" : fileName.trim();
        if (raw.isBlank()) {
            return "bild";
        }

        StringBuilder builder = new StringBuilder();
        for (char character : raw.toCharArray()) {
            if (Character.isLetterOrDigit(character) || character == '.' || character == '-' || character == '_') {
                builder.append(character);
            } else {
                builder.append('_');
            }
        }
        return builder.isEmpty() ? "bild" : builder.toString();
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }

    public Set<String> allowedContentTypes() {
        return new LinkedHashSet<>(ALLOWED_TYPES);
    }

    public record StoredAsset(
            String assetId,
            String publicUrl,
            String contentType,
            String originalName,
            byte[] bytes,
            long sizeBytes
    ) {
    }
}
