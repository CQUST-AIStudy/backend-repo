package com.tap.backend.service.ziporganize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ZipOrganizeNaming {
  private ZipOrganizeNaming() {}

  static final Set<String> BLOCKED_EXT = Set.of("exe", "bat", "cmd", "sh", "ps1", "dll", "so", "msi", "com");

  static String normalizeZipEntryPath(String raw) {
    String path = raw == null ? "" : raw.replace('\\', '/').trim();
    while (path.startsWith("/")) path = path.substring(1);
    path = path.replaceAll("/+", "/");
    if (path.isBlank()) throw new IllegalArgumentException("zip entry path is blank");
    List<String> parts = new ArrayList<>();
    for (String part : path.split("/")) {
      if (part.isBlank() || ".".equals(part)) continue;
      if ("..".equals(part)) throw new IllegalArgumentException("zip entry path contains traversal");
      parts.add(part);
    }
    if (parts.isEmpty()) throw new IllegalArgumentException("zip entry path is blank");
    return String.join("/", parts);
  }

  static String filenameOf(String path) {
    String value = path == null ? "file" : path;
    int idx = value.lastIndexOf('/');
    return idx >= 0 ? value.substring(idx + 1) : value;
  }

  static String extOf(String filename) {
    if (filename == null) return "";
    int dot = filename.lastIndexOf('.');
    return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
  }

  static String guessContentType(String filename) {
    String ext = extOf(filename);
    return switch (ext) {
      case "pdf" -> "application/pdf";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "doc" -> "application/msword";
      case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
      case "txt" -> "text/plain; charset=utf-8";
      case "md" -> "text/markdown; charset=utf-8";
      case "csv" -> "text/csv; charset=utf-8";
      case "json" -> "application/json";
      default -> "application/octet-stream";
    };
  }

  static String sanitizeFilename(String raw, String fallbackExt) {
    String name = raw == null ? "" : raw.trim();
    name = name.replace('\\', '_').replace('/', '_').replace(':', '-');
    name = name.replaceAll("[<>\"|?*\\x00-\\x1f]+", "_");
    name = name.replaceAll("\\s+", " ").trim();
    name = name.replaceAll("^\\.+|\\.+$", "");
    if (name.isBlank()) name = "unnamed";
    if (extOf(name).isBlank() && fallbackExt != null && !fallbackExt.isBlank()) {
      name = name + "." + fallbackExt;
    }
    if (name.length() > 220) {
      String ext = extOf(name);
      if (!ext.isBlank()) {
        int baseLen = Math.max(1, 220 - ext.length() - 1);
        name = name.substring(0, Math.min(baseLen, name.length())) + "." + ext;
      } else {
        name = name.substring(0, 220);
      }
    }
    return name;
  }

  static String sanitizeFolderPath(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String path = raw.replace('\\', '/').trim().replaceAll("/+", "/");
    List<String> parts = new ArrayList<>();
    for (String part : path.split("/")) {
      String p = part.trim();
      if (p.isBlank() || ".".equals(p) || "..".equals(p)) continue;
      p = p.replace(':', '-').replaceAll("[<>\"|?*\\x00-\\x1f]+", "_");
      p = p.replaceAll("\\s+", " ").trim();
      if (!p.isBlank()) parts.add(p.length() > 80 ? p.substring(0, 80) : p);
    }
    return String.join("/", parts);
  }

  static String buildRelativePath(String folder, String filename) {
    String cleanFolder = sanitizeFolderPath(folder);
    String cleanFilename = sanitizeFilename(filename, extOf(filename));
    return cleanFolder.isBlank() ? cleanFilename : cleanFolder + "/" + cleanFilename;
  }

  static String ensureUniquePath(String candidate, java.util.Set<String> used) {
    String normalized = candidate.replace('\\', '/').replaceAll("/+", "/");
    if (used.add(normalized)) return normalized;
    String filename = filenameOf(normalized);
    String ext = extOf(filename);
    String folder = normalized.contains("/") ? normalized.substring(0, normalized.lastIndexOf('/')) : "";
    String stem = ext.isBlank() ? filename : filename.substring(0, filename.length() - ext.length() - 1);
    for (int i = 2; ; i++) {
      String nextName = ext.isBlank() ? stem + "_" + i : stem + "_" + i + "." + ext;
      String next = folder.isBlank() ? nextName : folder + "/" + nextName;
      if (used.add(next)) return next;
    }
  }
}
