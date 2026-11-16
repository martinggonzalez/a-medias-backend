package com.amedias.receipt;

import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_SIDE = 12_000;
    private static final long MAX_PIXELS = 80_000_000L;

    private final GeminiReceiptReader reader;
    private final AnalyzeLimiter limiter;

    public ReceiptController(GeminiReceiptReader reader, AnalyzeLimiter limiter) {
        this.reader = reader;
        this.limiter = limiter;
    }

    /** La imagen no se guarda: solo se envía al proveedor de IA y se descarta. */
    @PostMapping("/analyze")
    public ReceiptAnalysis analyze(@AuthenticationPrincipal Jwt jwt, @RequestPart("image") MultipartFile image)
            throws IOException {
        if (image.isEmpty() || image.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "La foto debe pesar entre 1 B y 8 MB");
        }
        byte[] bytes = image.getBytes();
        // Se decide por el contenido real, no por el Content-Type ni la extensión que declara el cliente.
        ImageInspector.Info info = ImageInspector.inspect(bytes);
        if (info == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Usa una imagen JPG, PNG o WebP");
        }
        if (info.width() > MAX_SIDE || info.height() > MAX_SIDE || (long) info.width() * info.height() > MAX_PIXELS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Las dimensiones de la imagen son demasiado grandes");
        }

        UUID household = UUID.fromString(jwt.getClaimAsString("hid"));
        limiter.acquire(household);
        try {
            return reader.read(bytes, info.mime());
        } finally {
            limiter.release(household);
        }
    }
}
