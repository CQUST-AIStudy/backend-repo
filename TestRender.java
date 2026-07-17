import com.tap.backend.service.AnnotatedStudentReportService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TestRender {
    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        byte[] source = Files.readAllBytes(input);
        AnnotatedStudentReportService service = new AnnotatedStudentReportService();
        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                input.getFileName().toString(),
                source,
                "胡灵",
                new BigDecimal("84"),
                "报告整体完成度尚可，目标表格中的成绩应以可见课程目标分项为准。",
                List.of("目标1完成较好", "目标2仍需补足实现与分析"),
                "张老师",
                List.of(),
                List.of(
                        new AnnotatedStudentReportService.DimensionScore("目标1", BigDecimal.ZERO),
                        new AnnotatedStudentReportService.DimensionScore("目标2", BigDecimal.ZERO)
                )
        );
        Files.createDirectories(output.getParent());
        Files.write(output, rendered.bytes());
        System.out.println("Rendered: " + output.toAbsolutePath() + " (" + rendered.bytes().length + " bytes)");
    }
}
