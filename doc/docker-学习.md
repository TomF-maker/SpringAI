好的，我为你整理两份完整的资料：一份是 `application.yml` 的完整配置，另一份是 Docker 从零到部署 Qdrant 的完整学习笔记。

---

## 📄 一、完整的 Spring Boot application.yml 配置

```yaml
spring:
  application:
    name: purchase-assistant

  # ========== Ollama 大模型配置 ==========
  ai:
    ollama:
      base-url: http://你的服务器ip:11434   # 云服务器公网IP + Ollama端口
      chat:
        options:
          model: deepseek-r1:1.5b              # 当前使用的模型
          temperature: 0.7                     # 控制回答随机性（0-1）
          num-ctx: 4096                        # 上下文窗口大小

    # ========== Qdrant 向量数据库配置 ==========
    vectorstore:
      qdrant:
        host: 你的服务器ip                  # 云服务器公网IP
        port: 6334                             # Qdrant gRPC 端口
        collection-name: purchase_docs         # 集合名称（类似表名）
        vector-size: 1536                      # DeepSeek-R1-1.5B 的向量维度
        initialize-schema: true                # 自动创建集合

  # ========== 文件上传配置 ==========
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

  # ========== Thymeleaf 模板配置 ==========
  thymeleaf:
    cache: false                               # 开发时关闭缓存
    encoding: UTF-8
    mode: HTML

server:
  port: 8080

logging:
  level:
    org.springframework.ai: DEBUG              # 开启 AI 相关调试日志
```

---

## 🐳 二、Docker 完整学习笔记（从零到部署 Qdrant）

### 1. Docker 是什么？

Docker 是一个容器化平台，可以把应用及其依赖打包成一个“容器”（轻量级虚拟机），在任何系统上都能一致运行。

| 概念 | 类比 |
|:---|:---|
| **镜像 (Image)** | 类似于“安装盘”或“程序安装包” |
| **容器 (Container)** | 镜像运行起来的实例，类似于“正在运行的程序” |
| **仓库 (Repository)** | 存放镜像的地方，如 Docker Hub |
| **数据卷 (Volume)** | 容器内部数据的持久化存储，容器删除数据不丢 |

---

### 2. Docker 核心操作流程

```bash
# 查看 Docker 版本（验证安装）
docker --version

# 查看当前运行的容器
docker ps

# 查看所有容器（包括已停止的）
docker ps -a

# 查看所有镜像
docker images

# 从仓库拉取镜像
docker pull 镜像名:标签

# 创建并运行容器
docker run -d --name 容器名 -p 宿主机端口:容器端口 镜像名:标签

# 查看容器日志
docker logs 容器名

# 进入容器内部（调试用）
docker exec -it 容器名 /bin/bash

# 停止容器
docker stop 容器名

# 启动已停止的容器
docker start 容器名

# 重启容器
docker restart 容器名

# 删除容器（需先停止）
docker rm 容器名

# 删除镜像（需先删除依赖的容器）
docker rmi 镜像名
```

---

### 3. 参数详解：`docker run` 常用选项

| 参数 | 含义 |
|:---|:---|
| `-d` | 后台运行（detach），不阻塞终端 |
| `--name` | 给容器命名，便于后续管理 |
| `-p 宿主机端口:容器端口` | 端口映射，让外部能访问容器内的服务 |
| `-v 宿主机目录:容器目录` | 数据卷挂载，容器数据存到宿主机上 |
| `--restart=always` | 容器退出或 Docker 重启后自动恢复运行 |
| `-e 环境变量=值` | 设置容器内的环境变量 |
| `-it` | 交互模式运行（用于进入容器内部） |

---

### 4. 我们在项目中的完整部署过程

#### 步骤一：安装 Docker

```bash
# Ubuntu 安装 Docker
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun

# 启动 Docker 并设置开机自启
sudo systemctl start docker
sudo systemctl enable docker
```

---

#### 步骤二：部署 Ollama（大模型服务）

```bash
# 拉取并运行 Ollama 容器
sudo docker run -d \
  --name ollama \                    # 容器命名为 ollama
  --restart=always \                # 自动重启
  -p 11434:11434 \                  # 将容器 11434 端口映射到宿主机 11434
  ollama/ollama                     # 镜像名

# 在容器内下载模型
sudo docker exec -it ollama ollama pull deepseek-r1:1.5b

# 测试模型是否正常
sudo docker exec -it ollama ollama run deepseek-r1:1.5b "你好"
```

---

#### 步骤三：部署 Qdrant（向量数据库）

```bash
# 拉取并运行 Qdrant 容器
sudo docker run -d \
  --name qdrant \                   # 容器命名为 qdrant
  --restart=always \                # 自动重启
  -p 6333:6333 \                    # HTTP API 端口（Web UI 和 REST 接口）
  -p 6334:6334 \                    # gRPC 端口（高性能通信）
  -v /root/qdrant_storage:/qdrant/storage \  # 数据持久化存储
  qdrant/qdrant                     # 镜像名
```

---

#### 步骤四：管理容器的常用命令

```bash
# 查看所有容器状态
sudo docker ps -a

# 查看 Ollama 日志（排查问题）
sudo docker logs ollama

# 查看 Qdrant 日志
sudo docker logs qdrant

# 重启服务（例如修改配置后）
sudo docker restart ollama
sudo docker restart qdrant

# 停止并删除容器（慎重操作，会丢失容器数据）
sudo docker stop ollama && sudo docker rm ollama
sudo docker stop qdrant && sudo docker rm qdrant

# 查看容器资源占用
sudo docker stats
```

---

### 5. 云服务器防火墙配置

部署完成后，需要在云服务商控制台放行以下端口：

| 端口 | 用途 | 服务 |
|:---|:---|:---|
| 11434 | REST API | Ollama |
| 6333 | HTTP API / Web UI | Qdrant |
| 6334 | gRPC API | Qdrant |

**操作位置**：腾讯云/阿里云控制台 → 轻量应用服务器 → 防火墙 → 添加规则

---

### 6. 验证部署成功

```bash
# 测试 Ollama
curl http://你的服务器ip:11434/api/generate \
  -d '{"model":"deepseek-r1:1.5b","prompt":"你好","stream":false}'

# 测试 Qdrant
curl http://你的服务器ip:6333/collections
# 应返回 {"result":{"collections":[]},"status":"ok",...}
```

---

### 7. Docker 常用命令速查表

| 命令 | 作用 |
|:---|:---|
| `docker ps` | 查看运行中的容器 |
| `docker ps -a` | 查看所有容器 |
| `docker images` | 查看所有镜像 |
| `docker pull 镜像名` | 下载镜像 |
| `docker run -d --name 容器名 镜像名` | 后台运行容器 |
| `docker exec -it 容器名 /bin/bash` | 进入容器内部 |
| `docker logs 容器名` | 查看容器日志 |
| `docker stop/start/restart 容器名` | 停止/启动/重启容器 |
| `docker rm 容器名` | 删除容器 |
| `docker rmi 镜像名` | 删除镜像 |
| `docker system prune -a` | 清理所有未使用的容器/镜像 |

---

### 8. 总结：项目部署全景图

```
┌─────────────────────────────────────────────────────────────┐
│                     腾讯云服务器（你的服务器ip）           │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────────────────────┐  │
│  │   Ollama 容器    │    │          Qdrant 容器           │  │
│  │  端口: 11434     │    │  端口: 6333 (HTTP) / 6334 (gRPC)│  │
│  │  模型: deepseek  │    │  集合: purchase_docs           │  │
│  │  -r1:1.5b       │    │  维度: 1536                   │  │
│  └────────┬────────┘    └────────────────┬────────────────┘  │
│           │                              │                   │
│           └──────────┬───────────────────┘                   │
│                      │                                       │
│                 防火墙已开放：                                │
│                 11434, 6333, 6334                           │
└──────────────────────┼───────────────────────────────────────┘
                       │ 公网 API 调用
                       ▼
┌─────────────────────────────────────────────────────────────┐
│              你的本地 Spring Boot 项目                       │
│  application.yml 配置指向: http://你的服务器ip:11434     │
│  Qdrant 配置指向: 你的服务器ip:6334                     │
└─────────────────────────────────────────────────────────────┘
```

这份文档包含了我们到目前为止用到的所有 Docker 知识和配置。你可以把它保存下来，后续部署其他服务时也能参考。🎯