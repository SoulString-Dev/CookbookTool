# 同步服务端的运行镜像。
#
# ⚠️ 镜像里**不编译**：本仓库的 Gradle 构建还要 Android SDK（:app 模块），
# 在 NAS 上装那一套不值当。先在开发机上跑：
#     bash scripts/sync-server-image.sh
# 它会把胖包拷到 deploy/cookbook-sync.jar，然后构建这个镜像。
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY cookbook-sync.jar /app/cookbook-sync.jar

# 数据目录（SQLite + 图片 + 配对码）放在卷上，重建容器不丢
ENV COOKBOOK_PORT=8080 \
    COOKBOOK_DATA=/data
VOLUME ["/data"]
EXPOSE 8080

# MaxRAMPercentage 让 JVM 跟着容器内存走，别在小 NAS 上按物理内存开堆
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/cookbook-sync.jar"]
