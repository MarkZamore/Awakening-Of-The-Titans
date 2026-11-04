import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Read-only network diagnostic using the pinned downloader's two-GET pattern. */
public final class McefDownloadTransportProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("One new output directory is required");
        Path output = Path.of(arguments[0]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Output directory must be new");
        Files.createDirectories(output);
        URL url = URI.create("https://mcef-download.cinemamod.com/java-cef-builds/a78e832f9f13c2c688caea3d04d8b84fcd238d94/windows_amd64.tar.gz.sha256").toURL();
        StringBuilder report = new StringBuilder("java=" + System.getProperty("java.runtime.version") + "\n");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        try {
            report.append("firstResponseCode=").append(connection.getResponseCode()).append('\n');
            report.append("firstContentLength=").append(connection.getContentLengthLong()).append('\n');
            report.append("firstContentType=").append(connection.getContentType()).append('\n');
            // Exact MCEF opens a second connection for body bytes. Diagnostic
            // timeouts bound only this standalone probe, not the game or mod.
            URLConnection bodyConnection = url.openConnection();
            bodyConnection.setConnectTimeout(15000); bodyConnection.setReadTimeout(15000);
            try (InputStream input = bodyConnection.getInputStream()) {
                byte[] bytes = input.readNBytes(16385);
                if (bytes.length > 16384) throw new IOException("Unexpected checksum body size");
                Files.write(output.resolve("official-checksum-response.bin"), bytes);
                report.append("secondResponseBytes=").append(bytes.length).append('\n');
                report.append("secondBodySha256=").append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append('\n');
                report.append("expectedArchiveHashPresent=").append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .contains("E98C385542620F31A594D6FC3C38ED6BCA8A547E24CED982A1339BB3333668BE")).append('\n');
            }
        } catch (Exception failure) { report.append("failure=").append(failure).append('\n'); }
        finally { connection.disconnect(); }
        Files.writeString(output.resolve("transport-report.txt"), report);
        System.out.print(report);
    }
}
