package onebot.model;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Resolved image data fetched from the OneBot API.
 *
 * <p>Wraps raw bytes, base64, MIME type, and the local temp file path
 * returned by Napcat.  Call {@link #saveTo(File)} to persist the image
 * to a plugin data directory.</p>
 */
public class ImageData {

    private final String file;       // original file identifier from MessageSegment
    private final String localPath;  // Napcat temp file path
    private final byte[] bytes;
    private final String mimeType;
    private final long size;

    ImageData(String file, String localPath, byte[] bytes, String mimeType, long size) {
        this.file = file;
        this.localPath = localPath;
        this.bytes = bytes;
        this.mimeType = mimeType;
        this.size = size;
    }

    /** Original OneBot file identifier (used to fetch the image). */
    public String getFile() { return file; }

    /** Local temp file path on the Napcat host (may not be accessible). */
    public String getLocalPath() { return localPath; }

    /** Raw image bytes. */
    public byte[] getBytes() { return bytes; }

    /** Image size in bytes. */
    public long getSize() { return size; }

    /** MIME type, e.g. {@code "image/png"} or {@code "image/jpeg"}. */
    public String getMimeType() { return mimeType; }

    /** Pure base64-encoded string (no data-URI prefix). */
    public String getBase64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Data URI ready to feed to vision LLMs.
     * Example: {@code "data:image/png;base64,iVBORw0KGgo..."}
     */
    public String getDataUri() {
        return "data:" + mimeType + ";base64," + getBase64();
    }

    /**
     * Save the image to a directory.  The filename is derived from the
     * original file identifier with the correct extension appended.
     *
     * @param dir target directory (created if missing)
     * @return the saved file
     */
    public File saveTo(File dir) throws IOException {
        if (!dir.exists()) dir.mkdirs();
        String ext = mimeType.substring(mimeType.indexOf('/') + 1);
        File out = new File(dir, file + "." + ext);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        return out;
    }

    @Override
    public String toString() {
        return "ImageData{file='" + file + "', mime='" + mimeType + "', size=" + size + "}";
    }

    // ---- Internal factory used by OneBotAPI ----

    public static ImageData fromApiResult(String file, byte[] bytes) {
        String mime = detectMime(bytes);
        return new ImageData(file, null, bytes, mime, bytes.length);
    }

    public static ImageData fromLocalFile(String file, String localPath) throws IOException {
        File f = new File(localPath);
        byte[] bytes = Files.readAllBytes(f.toPath());
        String mime = detectMime(bytes);
        return new ImageData(file, localPath, bytes, mime, bytes.length);
    }

    /**
     * Detect MIME type from magic bytes (file header).
     */
    private static String detectMime(byte[] bytes) {
        if (bytes.length < 4) return "application/octet-stream";
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;

        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "image/png";
        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "image/jpeg";
        // GIF: 47 49 46 38 (GIF8)
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return "image/gif";
        // BMP: 42 4D
        if (b0 == 0x42 && b1 == 0x4D) return "image/bmp";
        // WebP: 52 49 46 46 ... 57 45 42 50 (RIFF....WEBP)
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && bytes.length >= 12
                && bytes[8] == 0x57 && bytes[9] == 0x45
                && bytes[10] == 0x42 && bytes[11] == 0x50) return "image/webp";

        return "application/octet-stream";
    }
}
