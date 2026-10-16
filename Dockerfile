# ===== LinkAgent 主程序镜像=====
# 多阶段：Maven 统一构建 主程序 + 三个 MCP 模块 → JRE 运行（镜像自包含，docker-compose up 一键起）
# 密钥不在此文件出现：全部由 docker-compose 从 .env 注入环境变量

# ---------- 构建阶段 ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先 COPY pom 拉依赖缓存（改动少时复用镜像层），再 COPY 源码
COPY agent/pom.xml agent/pom.xml
COPY rag-mcp/pom.xml rag-mcp/pom.xml
COPY crawl-mcp/pom.xml crawl-mcp/pom.xml
COPY liepin-mcp/pom.xml liepin-mcp/pom.xml
RUN mvn -q -B dependency:go-offline -pl agent,rag-mcp,crawl-mcp,liepin-mcp -am || true
COPY . .
RUN mvn -q -B -DskipTests -pl agent,rag-mcp,crawl-mcp,liepin-mcp -am package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Python3（liepin-mcp 子进程调用猎聘 CLI 需要）+ curl（下载 vec0.so）
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 四个 jar：主程序 + 三个 MCP server（stdio 由主进程拉起）
COPY --from=build /build/agent/target/agent-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build /build/rag-mcp/target/rag-mcp-0.0.1-SNAPSHOT.jar /app/rag-mcp.jar
COPY --from=build /build/crawl-mcp/target/crawl-mcp-0.0.1-SNAPSHOT.jar /app/crawl-mcp.jar
COPY --from=build /build/liepin-mcp/target/liepin-mcp-0.0.1-SNAPSHOT.jar /app/liepin-mcp.jar

# 房源数据源（文本文件，34 城，随镜像分发；运行时挂载卷可更新）
COPY agent/rag_data/houses /app/rag_data/houses

# vec0 扩展：Windows 用 .dll（本机），Linux 容器用 .so（RAG_DATA_DIR 下，VEC0_EXT=vec0.so 指定）
RUN mkdir -p /app/rag_data \
    && curl -fsSL -o /tmp/vec0.tgz https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz \
    && tar -xzf /tmp/vec0.tgz --strip-components=1 -C /app/rag_data \
    && rm /tmp/vec0.tgz \
    && ls -la /app/rag_data

# 猎聘 CLI（vendored Python 项目）：venv 由 liepin-mcp 容器初始化到共享卷（见 docker-compose）
COPY mcp/liepin-cli /src/liepin-cli

# 日志 + 知识库数据 + 猎聘 CLI 共享卷挂载点
RUN mkdir -p /app/logs
VOLUME ["/app/logs", "/app/rag_data", "/cli"]

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]