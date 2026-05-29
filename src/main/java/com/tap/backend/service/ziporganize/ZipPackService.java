package com.tap.backend.service.ziporganize;

import com.tap.backend.domain.ziporganize.ZipOrganizeItemEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

@Service
public class ZipPackService {
  private final ObjectStorageService storage;

  public ZipPackService(ObjectStorageService storage) {
    this.storage = storage;
  }

  public byte[] buildZip(List<ZipOrganizeItemEntity> items, String readme, byte[] reportJson) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
      if (readme != null && !readme.isBlank()) {
        zos.putNextEntry(new ZipEntry("README.md"));
        zos.write(readme.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }
      if (reportJson != null && reportJson.length > 0) {
        zos.putNextEntry(new ZipEntry("report.json"));
        zos.write(reportJson);
        zos.closeEntry();
      }
      for (ZipOrganizeItemEntity item : items) {
        if (item.getFinalPath() == null || item.getFinalPath().isBlank()) continue;
        zos.putNextEntry(new ZipEntry(item.getFinalPath()));
        zos.write(storage.getBytes(item.getObjectKey()));
        zos.closeEntry();
      }
    }
    return baos.toByteArray();
  }
}
