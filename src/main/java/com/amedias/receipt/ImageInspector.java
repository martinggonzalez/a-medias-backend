package com.amedias.receipt;

/** Comprueba la firma real del archivo y lee sus dimensiones sin decodificar la imagen. */
final class ImageInspector {

    record Info(String mime, int width, int height) {}

    private ImageInspector() {}

    /** @return null si no es JPEG/PNG/WebP válido. */
    static Info inspect(byte[] b) {
        if (b.length >= 24 && u(b, 0) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R') {
            return info("image/png", be32(b, 16), be32(b, 20));
        }
        if (b.length >= 4 && u(b, 0) == 0xFF && u(b, 1) == 0xD8) {
            return jpeg(b);
        }
        if (b.length >= 30 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return webp(b);
        }
        return null;
    }

    private static Info jpeg(byte[] b) {
        int i = 2;
        while (i + 3 < b.length) {
            if (u(b, i) != 0xFF) {
                i++;
                continue;
            }
            int m = u(b, i + 1);
            if (m == 0xFF) {
                i++;
                continue;
            }
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                i += 2;
                continue;
            }
            int len = (u(b, i + 2) << 8) | u(b, i + 3);
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof) {
                if (i + 8 >= b.length) {
                    return null;
                }
                int h = (u(b, i + 5) << 8) | u(b, i + 6);
                int w = (u(b, i + 7) << 8) | u(b, i + 8);
                return info("image/jpeg", w, h);
            }
            if (len < 2) {
                return null;
            }
            i += 2 + len;
        }
        return null;
    }

    private static Info webp(byte[] b) {
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8 ":
                return info("image/webp", (u(b, 26) | (u(b, 27) << 8)) & 0x3FFF, (u(b, 28) | (u(b, 29) << 8)) & 0x3FFF);
            case "VP8L": {
                if (u(b, 20) != 0x2F) {
                    return null;
                }
                long bits = (long) u(b, 21) | ((long) u(b, 22) << 8) | ((long) u(b, 23) << 16) | ((long) u(b, 24) << 24);
                return info("image/webp", (int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1);
            }
            case "VP8X":
                return info("image/webp", 1 + (u(b, 24) | (u(b, 25) << 8) | (u(b, 26) << 16)),
                        1 + (u(b, 27) | (u(b, 28) << 8) | (u(b, 29) << 16)));
            default:
                return null;
        }
    }

    private static Info info(String mime, int w, int h) {
        return w > 0 && h > 0 ? new Info(mime, w, h) : null;
    }

    private static int u(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int be32(byte[] b, int i) {
        return (u(b, i) << 24) | (u(b, i + 1) << 16) | (u(b, i + 2) << 8) | u(b, i + 3);
    }
}
