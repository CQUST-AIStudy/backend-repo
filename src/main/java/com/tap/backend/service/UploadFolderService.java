package com.tap.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.upload.UploadFolderEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UploadFolderRepository;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.tap.common.api.Maps;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadFolderService {
  private final UploadFolderRepository uploadFolderRepository;
  private final DocumentIngestService documentIngestService;
  private final ObjectMapper objectMapper;

  public UploadFolderService(UploadFolderRepository uploadFolderRepository,
      DocumentIngestService documentIngestService,
      ObjectMapper objectMapper) {
    this.uploadFolderRepository = uploadFolderRepository;
    this.documentIngestService = documentIngestService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public UploadFolderEntity createFolder(UserEntity user, String folderName, String originalStructureJson) {
    UploadFolderEntity folder = new UploadFolderEntity();
    folder.setUser(user);
    folder.setFolderName(folderName == null || folderName.isBlank() ? ("upload-" + Instant.now()) : folderName.trim());
    folder.setOriginalStructureJson(originalStructureJson);
    return uploadFolderRepository.save(folder);
  }

  @Transactional
  public UploadResult uploadZip(UserEntity user, String folderName, InputStream zipStream) throws Exception {
    UploadFolderEntity folder = createFolder(user, folderName, null);

    List<Map<String, Object>> fileInfos = new ArrayList<>();
    List<DocumentIngestService.StoredDocument> stored = new ArrayList<>();

    try (ZipInputStream zis = new ZipInputStream(zipStream)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        String path = normalize(entry.getName());
        byte[] bytes = readAllBytes(zis);
        if (bytes.length == 0) continue;

        String contentType = DocumentIngestService.guessContentType(path);
        var doc = documentIngestService.ingestBytes(user, folder, path, contentType, bytes);
        stored.add(doc);
        fileInfos.add(Maps.of(
            "path", path,
            "size", bytes.length,
            "sha256", doc.sha256(),
            "reused", doc.reused()
        ));
      }
    }

    folder.setOriginalStructureJson(objectMapper.writeValueAsString(Maps.of("type", "zip", "files", fileInfos)));
    uploadFolderRepository.save(folder);
    return new UploadResult(folder.getId(), stored.size(), stored);
  }

  public record UploadResult(long uploadFolderId, int storedCount, List<DocumentIngestService.StoredDocument> documents) {}

  private static byte[] readAllBytes(InputStream in) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int r;
    while ((r = in.read(buf)) >= 0) bos.write(buf, 0, r);
    return bos.toByteArray();
  }

  private static String normalize(String path) {
    if (path == null) return "file";
    String p = path.replace('\\', '/');
    while (p.startsWith("/")) p = p.substring(1);
    p = p.replaceAll("/+", "/");
    return p.isBlank() ? "file" : p;
  }

  private static String filenameOf(String path) {
    String p = path == null ? "file" : path;
    int idx = p.lastIndexOf('/');
    return idx >= 0 ? p.substring(idx + 1) : p;
  }
}
