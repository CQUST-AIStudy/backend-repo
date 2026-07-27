FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS=""

# 错误演示真实执行沙箱依赖：python3 跑 code_tracer.py，gcc 编译学生 C 代码
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 gcc libc6-dev \
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
