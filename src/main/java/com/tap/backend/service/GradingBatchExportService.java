package com.tap.backend.service;

import com.tap.backend.domain.grading.GradingBatchEntity;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.GradingTaskStatus;
import com.tap.backend.repo.GradingBatchRepository;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.GradingTaskRepository;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the merged grading Excel workbook:
 * sheet 1 is a batch overview, sheet 2 aggregates all student scores across tasks.
 */
@Service
public class GradingBatchExportService {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final GradingBatchRepository batchRepo;
    private final GradingTaskRepository taskRepo;
    private final GradingSubmissionRepository submissionRepo;

    public GradingBatchExportService(GradingBatchRepository batchRepo,
                                     GradingTaskRepository taskRepo,
                                     GradingSubmissionRepository submissionRepo) {
        this.batchRepo = batchRepo;
        this.taskRepo = taskRepo;
        this.submissionRepo = submissionRepo;
    }

    /**
     * Exports all tasks of a batch into one merged workbook.
     * Tasks that are not COMPLETED appear in the overview but are excluded from the score sheet.
     */
    @Transactional(readOnly = true)
    public byte[] exportBatchExcel(Long batchId, Long teacherId, boolean includeComments) {
        GradingBatchEntity batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        if (!Objects.equals(batch.getTeacherId(), teacherId)) {
            throw new IllegalArgumentException("无权导出此批次");
        }
        List<GradingTaskEntity> tasks = taskRepo.findAllByBatchIdOrderByCreatedAtAsc(batchId);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("该批次下没有批改任务");
        }
        return buildWorkbook(batch.getName(), batch.getDisplayCode(), tasks, includeComments);
    }

    /**
     * Exports an ad-hoc selection of tasks into one merged workbook.
     * All selected tasks must belong to the teacher and be COMPLETED.
     */
    @Transactional(readOnly = true)
    public byte[] exportSelectedTasksExcel(List<Long> taskIds, Long teacherId, boolean includeComments) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new IllegalArgumentException("请先选择要导出的批改任务");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>(taskIds);
        List<GradingTaskEntity> tasks = taskRepo.findAllById(uniqueIds);
        if (tasks.size() != uniqueIds.size()) {
            throw new IllegalArgumentException("部分任务不存在或已删除");
        }
        List<String> notCompleted = new ArrayList<>();
        for (GradingTaskEntity task : tasks) {
            if (!Objects.equals(task.getTeacherId(), teacherId)) {
                throw new IllegalArgumentException("无权导出任务 " + taskLabel(task));
            }
            if (task.getStatus() != GradingTaskStatus.COMPLETED) {
                notCompleted.add(taskLabel(task));
            }
        }
        if (!notCompleted.isEmpty()) {
            throw new IllegalArgumentException("以下任务尚未完成，无法导出: " + String.join("、", notCompleted));
        }
        tasks.sort(Comparator.comparing(GradingTaskEntity::getCreatedAt));
        return buildWorkbook("勾选任务合并导出", null, tasks, includeComments);
    }

    private byte[] buildWorkbook(String title, String batchCode, List<GradingTaskEntity> tasks, boolean includeComments) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            CellStyle headerStyle = workbook.createCellStyle();
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            buildScoreSheet(workbook, headerStyle, tasks, includeComments);
            buildOverviewSheet(workbook, headerStyle, title, batchCode, tasks);

            workbook.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出合并 Excel 失败: " + e.getMessage(), e);
        }
    }

    private void buildOverviewSheet(XSSFWorkbook workbook, CellStyle headerStyle,
                                    String title, String batchCode, List<GradingTaskEntity> tasks) {
        Sheet sheet = workbook.createSheet("批次概览");
        int rowIdx = 0;

        rowIdx = writeInfoRow(sheet, rowIdx, headerStyle, "批次名称", title);
        if (batchCode != null && !batchCode.isBlank()) {
            rowIdx = writeInfoRow(sheet, rowIdx, headerStyle, "批次编号", batchCode);
        }
        rowIdx = writeInfoRow(sheet, rowIdx, headerStyle, "导出时间", TIME_FORMATTER.format(Instant.now()));
        rowIdx = writeInfoRow(sheet, rowIdx, headerStyle, "任务数", String.valueOf(tasks.size()));
        rowIdx++; // blank separator row

        String[] headers = {"任务编号", "评分标准", "状态", "总份数", "已完成", "失败", "创建时间", "备注"};
        Row headerRow = sheet.createRow(rowIdx++);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        for (GradingTaskEntity task : tasks) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(taskLabel(task));
            row.createCell(1).setCellValue(task.getRubric() != null ? safe(task.getRubric().getName()) : "");
            row.createCell(2).setCellValue(statusLabel(task.getStatus()));
            row.createCell(3).setCellValue(task.getTotalCount());
            row.createCell(4).setCellValue(task.getCompletedCount());
            row.createCell(5).setCellValue(task.getFailedCount());
            row.createCell(6).setCellValue(task.getCreatedAt() != null ? TIME_FORMATTER.format(task.getCreatedAt()) : "");
            row.createCell(7).setCellValue(task.getStatus() == GradingTaskStatus.COMPLETED ? "已纳入成绩汇总" : "未完成，未纳入成绩汇总");
        }

        autoSizeColumns(sheet, headers.length, rowIdx);
    }

    private void buildScoreSheet(XSSFWorkbook workbook, CellStyle headerStyle,
                                 List<GradingTaskEntity> tasks, boolean includeComments) {
        Sheet sheet = workbook.createSheet("成绩汇总");
        String[] headers = includeComments
                ? new String[]{"任务编号", "学号", "姓名", "班级", "成绩", "作业文件", "状态", "总评"}
                : new String[]{"任务编号", "学号", "姓名", "班级", "成绩", "作业文件", "状态"};

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;
        for (GradingTaskEntity task : tasks) {
            if (task.getStatus() != GradingTaskStatus.COMPLETED) {
                continue;
            }
            List<GradingSubmissionEntity> submissions = submissionRepo.findAllByTaskId(task.getId());
            for (GradingSubmissionEntity sub : submissions) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(taskLabel(task));
                row.createCell(1).setCellValue(safe(sub.getStudentNo()));
                row.createCell(2).setCellValue(safe(sub.getStudentName()));
                row.createCell(3).setCellValue(safe(sub.getClassName()));
                if (sub.getTotalScore() != null) {
                    row.createCell(4).setCellValue(sub.getTotalScore().doubleValue());
                } else {
                    row.createCell(4).setCellValue("");
                }
                row.createCell(5).setCellValue(safe(sub.getOriginalFilename()));
                row.createCell(6).setCellValue(submissionStatusLabel(sub));
                if (includeComments) {
                    row.createCell(7).setCellValue(safe(sub.getFinalReviewComment()));
                }
            }
        }

        autoSizeColumns(sheet, headers.length, rowIdx);
    }

    private int writeInfoRow(Sheet sheet, int rowIdx, CellStyle headerStyle, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(headerStyle);
        row.createCell(1).setCellValue(value != null ? value : "");
        return rowIdx + 1;
    }

    private String taskLabel(GradingTaskEntity task) {
        return task.getDisplayCode() != null && !task.getDisplayCode().isBlank()
                ? task.getDisplayCode()
                : "#" + task.getId();
    }

    private String submissionStatusLabel(GradingSubmissionEntity sub) {
        if (sub.getStatus() == null) return "";
        return switch (sub.getStatus()) {
            case PENDING -> "待批改";
            case PROCESSING -> "批改中";
            case SCORED -> "已评分";
            case NEED_MORE_EVIDENCE -> "需复核";
            case FAILED -> "失败";
        };
    }

    private String statusLabel(GradingTaskStatus status) {
        if (status == null) return "";
        return switch (status) {
            case PENDING -> "待处理";
            case PROCESSING -> "批改中";
            case FINALIZING -> "生成资源中";
            case COMPLETED -> "已完成";
            case FAILED -> "存在失败";
        };
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private void autoSizeColumns(Sheet sheet, int columnCount, int rowCount) {
        int[] maxWidths = new int[columnCount];
        for (int r = 0; r < rowCount; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < columnCount; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) continue;
                int width = calcDisplayWidth(getCellStringValue(cell));
                if (width > maxWidths[c]) maxWidths[c] = width;
            }
        }
        for (int c = 0; c < columnCount; c++) {
            int width = Math.min(maxWidths[c] + 2, 80);
            sheet.setColumnWidth(c, Math.max(width, 8) * 256);
        }
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case BLANK -> "";
            default -> "";
        };
    }

    /**
     * CJK characters take roughly twice the horizontal space of Latin characters in Excel.
     */
    private int calcDisplayWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (char c : text.toCharArray()) {
            if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0x3000 && c <= 0x303F)
                    || (c >= 0xFF00 && c <= 0xFFEF)
                    || (c >= 0x2000 && c <= 0x206F)) {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }
}
