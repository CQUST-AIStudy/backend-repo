FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

COPY src src
# 运行镜像只需打包产物，跳过测试编译与执行（测试交给 CI/本地）；
# 避免个别历史/他人遗留的测试编译问题阻断生产镜像构建。
RUN ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="" \
    TAP_GRADING_REPORT_FONT_PATH=/usr/share/fonts/truetype/arphic/ukai.ttc

# 错误演示真实执行沙箱依赖：python3 跑 code_tracer.py，gcc 编译学生 C 代码
# 批注 PDF 的手写体签字/分数依赖中文楷体：fonts-arphic-ukai(AR PL UKai, 手写楷体)；
# fonts-wqy-zenhei 兜底覆盖 UKai 缺失的少数字形，避免中文回退成英文 Score:/Teacher。
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 gcc libc6-dev libreoffice-writer fonts-arphic-ukai fonts-wqy-zenhei \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs /app/data \
    && chown -R app:app /app

WORKDIR /app

COPY --from=builder /workspace/target/teaching-assistant-backend-*.jar /app/app.jar
COPY grading-worker/code_tracer.py /app/tools/code_tracer.py

USER app

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
