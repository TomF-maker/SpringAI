好的，我来帮你编写一份专业的 README.md 文件。这份文档可以直接放在 GitHub 仓库根目录，用于展示你的项目。

---

## 📄 README.md

```markdown
# 🛒 采购智能助手 - 企业级知识库系统

> 基于 Spring AI + Qdrant + Ollama 构建的智能采购知识库平台

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-blue)
![Qdrant](https://img.shields.io/badge/Qdrant-1.19.0-red)
![Ollama](https://img.shields.io/badge/Ollama-0.5.0-orange)
![Java](https://img.shields.io/badge/Java-21-blue)
![License](https://img.shields.io/badge/License-MIT-green)

---

## 📖 项目简介

**采购智能助手** 是一个面向企业采购场景的智能知识库系统。它结合了大语言模型（LLM）和向量检索技术（RAG），帮助企业高效管理采购文档、快速检索信息，并基于文档内容提供智能问答服务。

**核心价值**：
- 📄 **文档智能管理**：支持 PDF、Word、TXT 等多格式文档上传与向量化存储
- 🔍 **RAG 智能问答**：基于文档内容的精准问答，支持流式输出
- 🔐 **企业级权限控制**：基于部门和角色的文档可见性管理
- 🌐 **多源文档导入**：支持本地文件上传和 URL 远程导入
- 📊 **数据可视化**：实时统计文档、用户、部门数据

---

## 🏗️ 技术架构

### 后端技术栈

| 技术 | 版本 | 用途 |
|:---|:---|:---|
| Spring Boot | 4.1.0 | 应用框架 |
| Spring AI | 2.0.0 | AI 能力集成（Ollama、Qdrant） |
| Spring Security | 7.1.0 | 认证授权 + JWT |
| MyBatis-Plus | 3.5.7 | ORM 框架 |
| MySQL | 8.0 | 关系型数据库 |
| Redis | 7.x | 缓存 + 会话管理 |
| Qdrant | 1.19.0 | 向量数据库 |
| Ollama | 0.5.0 | 本地大模型部署 |
| Apache PDFBox | 2.0.30 | PDF 解析 |
| Apache POI | 5.3.0 | Word 文档解析 |
| Tesseract OCR | 5.3.0 | 图片文字识别 |

### 前端技术栈

| 技术 | 用途 |
|:---|:---|
| Thymeleaf | 模板引擎 |
| Bootstrap 5 | UI 组件库 |
| SweetAlert2 | 交互弹窗 |
| ECharts | 数据可视化 |

---

## ✨ 核心功能

### 1️⃣ 用户认证与权限管理
- **JWT 认证**：安全无状态的 Token 认证
- **双因素登录**：支持密码登录 + 邮箱验证码
- **基于角色的访问控制（RBAC）**：管理员 / 部门管理员 / 普通员工 / 外部用户

### 2️⃣ 组织架构管理
- 部门树形结构管理（无限级）
- 部门负责人指派
- 部门级数据隔离

### 3️⃣ 文档管理
- 本地文件上传（PDF、DOC、DOCX、TXT、MD）
- URL 远程导入（支持 AWS S3 预签名 URL）
- 文档自动解析与向量化
- 文档可见性控制（本部门 / 全公司 / 指定部门）
- 文档公开/内部切换
- 文档删除（联动删除向量数据）

### 4️⃣ RAG 智能问答
- 基于文档内容的精准问答
- 流式输出（实时逐字显示）
- 部门级权限过滤（用户仅可见本部门+公开文档）
- 本地知识库（常见问题秒级响应）

### 5️⃣ 数据看板
- 文档总数、用户数、部门数实时统计
- 近 7 天上传统计趋势图
- 文档类型占比分析

---

## 📁 项目结构

```
SpringAI/
├── src/main/java/com/example/springai/
│   ├── config/              # 配置类
│   │   ├── SecurityConfig   # Spring Security + JWT
│   │   ├── MyBatisPlusConfig
│   │   ├── RestTemplateConfig
│   │   └── WebConfig
│   ├── controller/          # 控制器
│   │   ├── AuthController   # 认证接口
│   │   ├── DocumentController # 文档管理接口
│   │   ├── DepartmentController # 部门管理接口
│   │   ├── UserController   # 用户管理接口
│   │   ├── RagController    # RAG 问答接口
│   │   └── PageController   # 页面路由
│   ├── service/             # 业务层
│   │   ├── impl/
│   │   │   ├── DocumentServiceImpl  # 文档核心业务
│   │   │   ├── RagServiceImpl       # RAG 问答业务
│   │   │   ├── UserDetailsServiceImpl # 认证用户加载
│   │   │   └── ...
│   ├── mapper/              # MyBatis-Plus Mapper
│   ├── entity/              # 实体类
│   ├── dto/                 # 数据传输对象
│   ├── filter/              # 过滤器
│   │   └── JwtAuthenticationFilter
│   ├── tool/                # 工具类
│   │   ├── ToolExecutor     # 工具调用执行器
│   │   ├── WeatherTool      # 天气查询工具
│   │   └── NewsTool         # 新闻抓取工具
│   └── utils/               # 通用工具
│       ├── JwtUtils
│       └── PasswordGenerator
├── src/main/resources/
│   ├── application.yml      # 核心配置文件
│   ├── templates/           # Thymeleaf 页面
│   │   ├── login.html
│   │   ├── register.html
│   │   ├── chat.html
│   │   ├── documents.html
│   │   ├── users.html
│   │   ├── departments.html
│   │   ├── profile.html
│   │   └── dashboard.html
│   └── static/              # 静态资源
└── pom.xml
```

---

## 🚀 快速启动

### 环境要求
- JDK 21+
- Maven 3.9+
- Docker（用于 Qdrant）
- MySQL 8.0
- Redis 7.x
- Ollama（本地大模型）

### 1️⃣ 克隆项目
```bash
git clone https://github.com/yourusername/spring-ai-knowledge-base.git
cd spring-ai-knowledge-base
```

### 2️⃣ 配置数据库
创建 MySQL 数据库：
```sql
CREATE DATABASE knowledge_base CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

执行 `docs/schema.sql` 初始化表结构。

### 3️⃣ 配置 application.yml
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/knowledge_base
    username: kb_user
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379
      password: your_redis_password
  mail:
    host: smtp.qq.com
    username: your_email@qq.com
    password: your_qq_auth_code

jwt:
  secret: your_256_bit_secret_key
```

### 4️⃣ 启动 Ollama
```bash
# 下载并运行 Ollama
docker run -d --name ollama --restart=always -p 11434:11434 ollama/ollama

# 下载模型
docker exec -it ollama ollama pull qwen2.5:1.5b
docker exec -it ollama ollama pull nomic-embed-text
```

### 5️⃣ 启动 Qdrant
```bash
docker run -d --name qdrant --restart=always \
  -p 6333:6333 -p 6334:6334 \
  -v /root/qdrant_storage:/qdrant/storage \
  qdrant/qdrant:latest
```

### 6️⃣ 启动应用
```bash
mvn clean package -DskipTests
java -jar target/SpringAI-0.0.1-SNAPSHOT.jar
```

### 7️⃣ 访问系统
- 登录页：`http://localhost:8080/login`
- 默认账号：`admin` / `admin123`

---

## 📸 功能预览

### 登录页
![登录页](./screenshots/login.png)

### 智能问答
![智能问答](./screenshots/chat.png)

### 文档管理
![文档管理](./screenshots/documents.png)

### 数据看板
![数据看板](./screenshots/dashboard.png)

> 📌 截图示例：请将实际截图放入 `screenshots/` 目录

---

## 📦 部署到服务器

### 1️⃣ 上传 JAR 包
```bash
scp target/SpringAI-0.0.1-SNAPSHOT.jar ubuntu@your-server-ip:/opt/apps/springai/
```

### 2️⃣ 后台运行
```bash
cd /opt/apps/springai
nohup java -jar SpringAI-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
```

### 3️⃣ 查看日志
```bash
tail -f /opt/apps/springai/app.log
```

---

## 🔧 运维命令

| 操作 | 命令 |
|:---|:---|
| 查看应用状态 | `ps aux | grep java` |
| 停止应用 | `pkill -f SpringAI-0.0.1-SNAPSHOT.jar` |
| 查看实时日志 | `tail -f /opt/apps/springai/app.log` |
| 重启应用 | `sh /opt/apps/springai/deploy.sh` |

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

## 📄 许可证

MIT License

---

## 📮 联系方式

- 作者：成思危
- 邮箱：2159341772@qq.com
- 项目地址：git@github.com:TomF-maker/SpringAI.git

---

**⭐ 如果这个项目对你有帮助，请给一个 Star！**
```

---

## 📌 使用说明

1. 在项目根目录创建 `README.md` 文件，复制上述内容。
2. 创建 `screenshots/` 目录，放入实际功能截图：
   - `login.png`：登录页
   - `chat.png`：智能问答页面
   - `documents.png`：文档管理页面
   - `dashboard.png`：数据看板
3. 修改以下占位信息：
   - `https://github.com/yourusername/spring-ai-knowledge-base.git`
   - `your_email@qq.com`
   - `your_qq_auth_code`
   - 作者姓名和邮箱

---

## 🚀 下一步

README 编写完成后，你可以：

1. **提交到 GitHub**：`git add README.md && git commit -m "docs: add README" && git push`
2. **继续开发数据看板**：让系统更专业
3. **开始准备面试**：整理项目亮点和演示流程

需要我帮你继续实现数据看板吗？🚀