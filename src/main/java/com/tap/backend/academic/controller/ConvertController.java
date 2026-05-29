package com.tap.backend.academic.controller;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ConvertController {

    private static final String LIBREOFFICE_WIN_PATH = "C:\\Program Files\\LibreOffice\\program\\soffice.exe";
    private static final String[] ALTERNATIVE_WIN_PATHS = {
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice 7\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice 7.5\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice 7.6\\program\\soffice.exe"
    };

    private final LegacySessionAccessResolver legacySessionAccessResolver;

    public ConvertController(LegacySessionAccessResolver legacySessionAccessResolver) {
        this.legacySessionAccessResolver = legacySessionAccessResolver;
    }

    @PostMapping("/api/convert-to-pdf")
    public ResponseEntity<byte[]> convertToPdf(
            @RequestParam("wordFile") MultipartFile wordFile,
            HttpServletRequest request) {
        legacySessionAccessResolver.requireTeacherOrAdmin(request);

        if (wordFile == null || wordFile.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String originalFilename = wordFile.getOriginalFilename();
        String extension = getNormalizedExtension(originalFilename);
        if (!"docx".equals(extension) && !"doc".equals(extension)) {
            return ResponseEntity.badRequest().build();
        }

        String tempDir = System.getProperty("java.io.tmpdir");
        String uniqueId = UUID.randomUUID().toString();
        Path wordPath = Paths.get(tempDir, uniqueId + "." + extension);
        Path actualPdfPath = null;

        try {
            wordFile.transferTo(wordPath.toFile());

            String officePath = getLibreOfficePath();
            if (officePath == null) {
                throw new IllegalStateException("LibreOffice executable not found");
            }

            ProcessBuilder pb = new ProcessBuilder(
                    officePath,
                    "--headless",
                    "--convert-to", "pdf",
                    "--outdir", tempDir,
                    wordPath.toString()
            );
            pb.directory(new File(tempDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
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

            String expectedPdfName = wordPath.getFileName().toString()
                    .replace("." + extension, ".pdf");
            actualPdfPath = findGeneratedPdfFile(tempDir, uniqueId, expectedPdfName);
            if (actualPdfPath == null) {
                throw new IllegalStateException("Generated PDF not found");
            }

            byte[] pdfContent = Files.readAllBytes(actualPdfPath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "report.pdf");
            return ResponseEntity.ok().headers(headers).body(pdfContent);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(500).build();
        } finally {
            deleteQuietly(wordPath);
            deleteQuietly(actualPdfPath);
        }
    }

    private String getLibreOfficePath() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            File file = new File(LIBREOFFICE_WIN_PATH);
            if (file.exists() && file.canExecute()) {
                return LIBREOFFICE_WIN_PATH;
            }
            for (String path : ALTERNATIVE_WIN_PATHS) {
                file = new File(path);
                if (file.exists() && file.canExecute()) {
                    return path;
                }
            }
            return null;
        }
        return "soffice";
    }

    private Path findGeneratedPdfFile(String directory, String uniqueId, String expectedFileName) {
        Path expectedPath = Paths.get(directory, expectedFileName);
        if (Files.exists(expectedPath)) {
            return expectedPath;
        }

        File dir = new File(directory);
        File[] files = dir.listFiles((d, name) ->
                (name.startsWith(uniqueId) || name.contains(uniqueId.substring(0, 5)))
                        && name.toLowerCase(Locale.ROOT).endsWith(".pdf"));
        if (files != null && files.length > 0) {
            return files[0].toPath();
        }
        return null;
    }

    private String getNormalizedExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
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
