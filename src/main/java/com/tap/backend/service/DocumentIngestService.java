package com.tap.backend.service;

import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.domain.upload.UploadFolderEntity;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.infra.crypto.Digests;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.infra.text.FileTextExtractor;
import com.tap.backend.infra.text.LanguageHeuristic;
import com.tap.backend.repo.DocumentRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentIngestService {
  private final DocumentRepository documentRepository;
  private final ObjectStorageService objectStorageService;
  private final FileTextExtractor fileTextExtractor;
  private final DocumentIngestProperties props;

  public DocumentIngestService(DocumentRepository documentRepository,
      ObjectStorageService objectStorageService,
      FileTextExtractor fileTextExtractor,
      DocumentIngestProperties props) {
    this.documentRepository = documentRepository;
    this.objectStorageService = objectStorageService;
    this.fileTextExtractor = fileTextExtractor;
    this.props = props;
  }

  public record StoredDocument(long id, String relativePath, String sha256, String objectKey, boolean reused) {}

  @Transactional
  public List<StoredDocument> ingestMultipartFiles(UserEntity user, UploadFolderEntity folder,
      List<MultipartFile> files, List<String> relativePaths) throws Exception {
    if (!relativePaths.isEmpty() && relativePaths.size() != files.size()) {
      throw new IllegalArgumentException("relativePaths length must match files length");
    }
    List<StoredDocument> stored = new ArrayList<>();
    for (int i = 0; i < files.size(); i++) {
      MultipartFile mf = files.get(i);
      if (mf.isEmpty()) continue;

      String rel = normalize(relativePaths.isEmpty() ? mf.getOriginalFilename() : relativePaths.get(i));
      byte[] bytes = mf.getBytes();
      String contentType = (mf.getContentType() == null || mf.getContentType().isBlank())
          ? guessContentType(rel)
          : mf.getContentType();
      stored.add(ingestBytes(user, folder, rel, contentType, bytes));
    }
    return stored;
  }

  @Transactional
  public StoredDocument ingestBytes(UserEntity user, UploadFolderEntity folder,
      String relativePath, String contentType, byte[] bytes) {
    String rel = normalize(relativePath);
    String sha256 = Digests.sha256Hex(bytes);

    var existing = documentRepository.findFirstBySha256OrderByIdAsc(sha256);
    boolean reused = existing.isPresent();

    String objectKey;
    String extractedText;
    String extractedTextKey;
    boolean truncated;
    String language;

    if (reused) {
      DocumentEntity ex = existing.get();
      objectKey = ex.getObjectKey();
      extractedText = ex.getExtractedText();
      extractedTextKey = ex.getExtractedTextKey();
      truncated = ex.isExtractedTextTruncated();
      language = ex.getLanguage();
    } else {
      objectKey = "objects/%s".formatted(sha256);
      objectStorageService.putBytes(objectKey, bytes, contentType);

      String full = fileTextExtractor.extract(rel, contentType, bytes);
      language = LanguageHeuristic.detect(full);

      int maxChars = props.extractedTextMaxChars() <= 0 ? 20000 : props.extractedTextMaxChars();
      if (full != null && full.length() > maxChars) {
        truncated = true;
        extractedText = full.substring(0, maxChars);
      } else {
        truncated = false;
        extractedText = full;
      }

      extractedTextKey = null;
      if (props.storeFullExtractedTextToMinio() && full != null && !full.isBlank()) {
        extractedTextKey = "extracted/%s.txt".formatted(sha256);
        objectStorageService.putBytes(extractedTextKey, full.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
      }
    }

    DocumentEntity doc = new DocumentEntity();
    doc.setUser(user);
    doc.setUploadFolder(folder);
    doc.setOriginalPath(rel);
    doc.setFilename(filenameOf(rel));
    doc.setContentType(contentType == null || contentType.isBlank() ? guessContentType(rel) : contentType);
    doc.setSizeBytes(bytes.length);
    doc.setSha256(sha256);
    doc.setObjectKey(objectKey);
    doc.setExtractedText(extractedText);
    doc.setExtractedTextKey(extractedTextKey);
    doc.setExtractedTextTruncated(truncated);
    doc.setLanguage(language);
    doc = documentRepository.save(doc);

    return new StoredDocument(doc.getId(), rel, sha256, objectKey, reused);
  }

  static String normalize(String path) {
    if (path == null) return "file";
    String p = path.replace('\\', '/');
    while (p.startsWith("/")) p = p.substring(1);
    p = p.replaceAll("/+", "/");
    return p.isBlank() ? "file" : p;
  }

  static String filenameOf(String path) {
    String p = path == null ? "file" : path;
    int idx = p.lastIndexOf('/');
    return idx >= 0 ? p.substring(idx + 1) : p;
  }

  static String guessContentType(String filename) {
    String n = (filename == null ? "" : filename).toLowerCase();
    if (n.endsWith(".pdf")) return "application/pdf";
    if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (n.endsWith(".doc")) return "application/msword";
    if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
    return "application/octet-stream";
  }
}
