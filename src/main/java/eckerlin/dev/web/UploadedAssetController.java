package eckerlin.dev.web;

import eckerlin.dev.services.UploadedAssetService;
import eckerlin.dev.web.dto.DashboardSession;
import eckerlin.dev.web.dto.UploadedAssetView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Duration;

@RestController
@RequestMapping
public class UploadedAssetController {

    private final UploadedAssetService uploadedAssetService;

    public UploadedAssetController(UploadedAssetService uploadedAssetService) {
        this.uploadedAssetService = uploadedAssetService;
    }

    @PostMapping(value = "/api/assets/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadedAssetView upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request,
            HttpSession session
    ) {
        DashboardSession dashboardSession = requireSession(session);
        try {
            UploadedAssetService.StoredAsset storedAsset = uploadedAssetService.store(file, dashboardSession.userId());
            String publicUrl = storedAsset.publicUrl().startsWith("/")
                    ? ServletUriComponentsBuilder.fromRequestUri(request)
                    .replacePath(storedAsset.publicUrl())
                    .replaceQuery(null)
                    .build()
                    .toUriString()
                    : storedAsset.publicUrl();
            return new UploadedAssetView(
                    storedAsset.assetId(),
                    publicUrl,
                    storedAsset.contentType(),
                    storedAsset.originalName(),
                    storedAsset.sizeBytes()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Das Bild konnte nicht gespeichert werden.");
        }
    }

    @GetMapping("/media/{assetId}")
    public ResponseEntity<byte[]> media(@PathVariable String assetId) {
        UploadedAssetService.StoredAsset asset = uploadedAssetService.find(assetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bild nicht gefunden."));

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(asset.contentType());
        } catch (RuntimeException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(asset.bytes());
    }

    private DashboardSession requireSession(HttpSession session) {
        Object user = session.getAttribute(DashboardController.SESSION_USER);
        if (user instanceof DashboardSession dashboardSession) {
            return dashboardSession;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst ueber Discord anmelden.");
    }
}
