package com.tap.backend.api.uploads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.upload.UploadFolderEntity;
import com.tap.backend.repo.UploadFolderRepository;
import com.tap.backend.service.DocumentIngestService;
import com.tap.backend.service.UploadFolderService;
import com.tap.backend.service.UserService;
import com.tap.backend.audit.AuditAction;
import com.tap.backend.audit.AuditService;
import com.tap.backend.security.PrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads/folders")
public class UploadsFolderController {
  private final UserService userService;
  private final UploadFolderService uploadFolderService;
  private final UploadFolderRepository uploadFolderRepository;
  private final DocumentIngestService documentIngestService;
  private final ObjectMapper objectMapper;
  private final AuditService auditService;
  private final PrincipalResolver principalResolver;

  public UploadsFolderController(UserService userService,
      UploadFolderService uploadFolderService,
      UploadFolderRepository uploadFolderRepository,
      DocumentIngestService documentIngestService,
      ObjectMapper objectMapper,
      AuditService auditService,
      PrincipalResolver principalResolver) {
    this.userService = userService;
    this.uploadFolderService = uploadFolderService;
    this.uploadFolderRepository = uploadFolderRepository;
    this.documentIngestService = documentIngestService;
    this.objectMapper = objectMapper;
    this.auditService = auditService;
    this.principalResolver = principalResolver;
  }

  public record CreateUploadFolderRequest(
      @Size(max = 256) String folderName,
      String originalStructureJson
  ) {}

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse<Map<String, Object>> createFolder(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @Valid @RequestBody CreateUploadFolderRequest req
  ) {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    UploadFolderEntity folder = uploadFolderService.createFolder(user, req.folderName(), req.originalStructureJson());
    auditService.record(resolved, AuditAction.UPLOAD_FOLDER_CREATE, "UploadFolder", String.valueOf(folder.getId()),
        Maps.of("folderName", folder.getFolderName()), request);
    return ApiResponse.of(Maps.of(
        "id", folder.getId(),
        "folderName", folder.getFolderName(),
        "createdAt", folder.getCreatedAt()
    ));
  }

  @PostMapping(value = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> uploadFiles(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @PathVariable("id") long uploadFolderId,
      @RequestPart("files") List<MultipartFile> files,
      @RequestParam(value = "relativePaths", required = false) String relativePathsJson
  ) throws Exception {
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    UploadFolderEntity folder = uploadFolderRepository.findByIdAndUser_Id(uploadFolderId, user.getId())
        .orElseThrow(() -> new IllegalArgumentException("upload folder not found"));

    List<String> relativePaths = parseRelativePaths(relativePathsJson);
    List<DocumentIngestService.StoredDocument> stored = documentIngestService.ingestMultipartFiles(user, folder, files, relativePaths);
    if ((folder.getOriginalStructureJson() == null || folder.getOriginalStructureJson().isBlank()) && !relativePaths.isEmpty()) {
      folder.setOriginalStructureJson(objectMapper.writeValueAsString(Maps.of("type", "multipart", "files", relativePaths)));
      uploadFolderRepository.save(folder);
    }
    long bytes = files.stream().mapToLong(f -> f == null ? 0 : f.getSize()).sum();
    long reused = stored.stream().filter(DocumentIngestService.StoredDocument::reused).count();
    auditService.record(resolved, AuditAction.UPLOAD_FOLDER_FILES, "UploadFolder", String.valueOf(folder.getId()),
        Maps.of("storedCount", stored.size(), "filesCount", files.size(), "bytes", bytes, "reusedCount", reused), request);
    return ApiResponse.of(Maps.of(
        "uploadFolderId", folder.getId(),
        "storedCount", stored.size(),
        "documents", stored
    ));
  }

  @PostMapping(value = "/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> uploadZip(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request,
      @RequestParam(value = "folderName", required = false) String folderName,
      @RequestPart("file") MultipartFile file
  ) throws Exception {
    if (file.isEmpty()) throw new IllegalArgumentException("zip file is empty");
    var resolved = principalResolver.resolve(principal);
    var user = userService.requireById(resolved.userId());
    var result = uploadFolderService.uploadZip(user, folderName, file.getInputStream());
    auditService.record(resolved, AuditAction.UPLOAD_FOLDER_ZIP, "UploadFolder", String.valueOf(result.uploadFolderId()),
        Maps.of("storedCount", result.storedCount(), "zipBytes", file.getSize()), request);
    return ApiResponse.of(Maps.of(
        "uploadFolderId", result.uploadFolderId(),
        "storedCount", result.storedCount(),
        "documents", result.documents()
    ));
  }

  private List<String> parseRelativePaths(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("relativePaths must be JSON array of strings");
    }
  }
}
