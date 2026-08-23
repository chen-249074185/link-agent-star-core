# spike 目录说明

技术预研产物（不参与主项目构建），保留供 RAG MCP 后续使用。

## sqlite-vec/

- `src/Vec0Spike.java`：D37 决策 spike——验证 sqlite-vec (vec0.dll) 在 Windows + sqlite-jdbc 下加载 + KNN 查询
  - 本地运行：`javac -cp sqlite-jdbc.jar Vec0Spike.java && java -cp "sqlite-jdbc.jar;slf4j-api.jar;logback-classic.jar;logback-core.jar;." Vec0Spike`
  - 注意：sqlite-jdbc 运行时需 slf4j + logback（NoClassDefFoundError）
- `vec0.dll`：sqlite-vec v0.1.9 Windows x64 预编译扩展（GitHub Releases 下载，rag-mcp 部署时复制到 jar 同目录）
- `vec.tar.gz` / `install.sh`：下载源备份
- **D37 结论：方案 A 通过**——vec0.dll 加载成功、建表/插入/KNN 查询全通过，采用 sqlite-vec 正式路线

## rag-handshake/

- `RagMcpHandshakeTest.java`：RAG MCP 端到端握手测试（本地跑，需要 DASHSCOPE_API_KEY）
  - 拉起 rag-mcp fat jar 子进程 → initialize → listTools → 上传 3 条 → 检索（命中北京）→ user2 隔离验证 → 删库
  - 运行：先 `mvn package`（rag-mcp 目录），再 `java -cp "rag-mcp.jar;." RagMcpHandshakeTest`