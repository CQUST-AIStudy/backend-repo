package com.tap.backend.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OfficeDocumentConversionService {
    private static final String LIBREOFFICE_WIN_PATH = "C:\\Program Files\\LibreOffice\\program\\soffice.exe";
    private static final String[] ALTERNATIVE_WIN_PATHS = {
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice 7\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice 7.5\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice 7.6\\program\\soffice.exe"
    };

    public byte[] convertWordToPdf(String originalFilename, byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("Source document is empty");
        }

        String extension = resolveExtension(originalFilename);
        if (!"doc".equals(extension) && !"docx".equals(extension)) {
            throw new IllegalArgumentException("Only doc/docx can be converted to PDF");
        }

        String uniqueId = UUID.randomUUID().toString();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path sourcePath = tempDir.resolve(uniqueId + "." + extension);
        Path outputPdfPath = tempDir.resolve(uniqueId + ".pdf");

        try {
            Files.write(sourcePath, sourceBytes);

            String officePath = getLibreOfficePath();
            if (officePath == null || officePath.isBlank()) {
                throw new IllegalStateException("LibreOffice executable not found");
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                    officePath,
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", tempDir.toString(),
                    sourcePath.toString()
            );
            processBuilder.directory(tempDir.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("LibreOffice conversion failed: " + exitCode + "\n" + output);
            }

            Path actualPdfPath = Files.exists(outputPdfPath)
                    ? outputPdfPath
                    : findGeneratedPdf(tempDir, uniqueId);
            if (actualPdfPath == null || !Files.exists(actualPdfPath)) {
                throw new IllegalStateException("Generated PDF not found");
            }
            return Files.readAllBytes(actualPdfPath);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to convert Word document to PDF", e);
        } finally {
            deleteQuietly(sourcePath);
            deleteQuietly(outputPdfPath);
        }
    }

    private Path findGeneratedPdf(Path tempDir, String uniqueId) throws IOException {
        try (var stream = Files.list(tempDir)) {
            return stream
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".pdf") && name.contains(uniqueId.substring(0, 5));
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private String getLibreOfficePath() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            Path path = Path.of(LIBREOFFICE_WIN_PATH);
            if (Files.exists(path)) {
                return path.toString();
            }
            for (String candidate : ALTERNATIVE_WIN_PATHS) {
                path = Path.of(candidate);
                if (Files.exists(path)) {
                    return path.toString();
                }
            }
            return null;
        }
        return "soffice";
    }

    private String resolveExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
