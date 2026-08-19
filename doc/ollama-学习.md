# Ollama 完整学习资料

> 一份从零开始的 Ollama 本地大模型运行指南


## 一、Ollama 是什么？

Ollama 是一款免费、开源的本地大语言模型（LLM）运行工具。它让你在自己电脑上直接运行 Llama、Qwen、DeepSeek、Gemma 等主流开源大模型，**不需要联网、不需要付费，数据也不出本机**。只需要一条命令，就能像聊天一样和本地大模型对话。

**核心特点**：

| 特点 | 说明 |
|------|------|
| **本地 & 私有** | 模型完全运行在你自己的硬件上，数据不离开你的机器 |
| **简单易用** | 简单的 CLI 命令即可拉取、运行和管理模型 |
| **模型库丰富** | 可访问数百个开源模型：ollama.com/library |
| **REST API** | 内置 HTTP API，可集成到任何应用中 |
| **跨平台** | 支持 macOS、Linux、Windows 和 Docker |
| **多后端加速** | 基于 llama.cpp 优化，支持 CPU 和 GPU 加速 |

**热门模型支持**：

- **Gemma 3** - Google 最新语言模型
- **Llama 3.2** - Meta 的强大语言模型
- **Mistral** - 高效高性能模型
- **Phi-3** - Microsoft 的紧凑型模型
- **DeepSeek R1** - 先进推理模型
- **Qwen** - 阿里巴巴的多语言模型


## 二、安装 Ollama

### 2.1 Windows 安装

1. 访问 https://ollama.com/download 下载 `OllamaSetup.exe`
2. 双击安装包，点击 Install，等待进度条走完
3. 安装完成后 Ollama 自动启动，系统托盘会出现 Ollama 图标

**使用 winget 安装**（如果已安装 winget）：
```powershell
winget install Ollama.Ollama
```

### 2.2 macOS 安装

下载 `Ollama.dmg` 并安装。

### 2.3 Linux 安装

使用一键安装脚本：
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

手动安装特定版本：
```bash
curl -fsSL https://ollama.com/install.sh | OLLAMA_VERSION=0.5.7 sh
```

### 2.4 Docker 安装

官方 Ollama Docker 镜像可在 Docker Hub 获取：
```bash
docker run -d --name ollama -p 11434:11434 ollama/ollama
```

### 2.5 修改模型存储路径（Windows 推荐）

Ollama 默认将模型存储到 C 盘（`C:\Users\用户名\.ollama\models`），建议修改到非系统盘。

**方法**：设置环境变量 `OLLAMA_MODELS`，指向新路径（如 `D:\OllamaModels`），然后重启 Ollama。


## 三、快速开始

### 3.1 运行第一个模型

最简单的启动方式：
```bash
ollama run gemma3
```

这条命令会：
- 自动下载 Gemma 3 模型（如果尚未下载）
- 启动 Ollama 服务器（如果未运行）
- 进入交互式对话会话

首次运行需要下载模型，7B 模型大约需要 4GB 存储空间。

### 3.2 交互式对话

进入对话后，你会看到 `>>>` 提示符：
```
>>> 为什么天空是蓝色的？
```
模型会实时流式输出回答。输入 `/help` 查看所有聊天命令，按 `Ctrl+D` 退出。

### 3.3 非交互式模式（单次提示）

不进入对话模式，直接获取回答：
```bash
# 单次提示
ollama run gemma3 "用简单的话解释量子计算"

# 管道输入
echo "写一首关于编程的短诗" | ollama run gemma3

# 保存输出
ollama run gemma3 "生成一个Python函数" > output.py
```


## 四、核心命令速查

### 4.1 模型管理

| 命令 | 作用 | 示例 |
|------|------|------|
| `ollama pull` | 下载模型（不运行） | `ollama pull qwen2:7b` |
| `ollama run` | 运行模型（自动下载） | `ollama run llama3.2` |
| `ollama list` / `ollama ls` | 列出已下载的模型 | `ollama list` |
| `ollama rm` | 删除模型，释放磁盘空间 | `ollama rm gemma3` |
| `ollama show` | 查看模型详情 | `ollama show qwen2:7b` |
| `ollama cp` | 复制模型并重命名 | `ollama cp qwen2:7b my-qwen` |

### 4.2 运行状态管理

| 命令 | 作用 | 示例 |
|------|------|------|
| `ollama ps` | 查看当前运行中的模型 | `ollama ps` |
| `ollama stop` | 停止运行中的模型 | `ollama stop qwen2:7b` |
| `ollama serve` | 启动 Ollama 服务 | `ollama serve` |

### 4.3 自定义模型

| 命令 | 作用 | 示例 |
|------|------|------|
| `ollama create` | 从 Modelfile 创建自定义模型 | `ollama create mymodel -f Modelfile` |
| `ollama push` | 将模型推送到仓库 | `ollama push mynamespace/mymodel` |

### 4.4 其他

| 命令 | 作用 | 示例 |
|------|------|------|
| `ollama launch` | 配置并启动外部应用集成 | `ollama launch claude` |
| `ollama signin` / `signout` | 登录/登出 Ollama | `ollama signin` |


## 五、模型推荐

| 模型 | 大小 | 特点 |
|------|------|------|
| `llama3.2:latest` | ~4.7GB | Meta 最新开源模型，综合能力强 |
| `mistral:7b` | ~4.1GB | 高效快速，适合日常使用 |
| `qwen2:7b` | ~4.4GB | 阿里出品，中文能力强 |
| `deepseek-r1:7b` | ~4.7GB | 深度求索推理模型，逻辑性强 |
| `phi3:mini` | ~2.3GB | Microsoft 紧凑模型，资源占用小 |
| `gemma3:latest` | ~2.0GB | Google 轻量高效模型 |

**新手建议**：先从较小的模型开始尝试，如 `llama3.2:latest` 或 `mistral:7b`。熟悉基本操作后，再尝试更大的模型或专用模型。


## 六、REST API 使用

Ollama 默认在 `http://localhost:11434` 提供 REST API 服务。

### 6.1 生成回答（/api/generate）

```bash
curl http://localhost:11434/api/generate -d '{
  "model": "qwen2:7b",
  "prompt": "为什么天空是蓝色的？"
}'
```

### 6.2 对话模式（/api/chat）

支持多轮对话上下文：
```bash
curl http://localhost:11434/api/chat -d '{
  "model": "qwen2:7b",
  "messages": [
    {"role": "user", "content": "为什么天空是蓝色的？"}
  ],
  "stream": false
}'
```

### 6.3 生成向量嵌入（/api/embeddings）

```bash
curl http://localhost:11434/api/embeddings -d '{
  "model": "nomic-embed-text",
  "prompt": "Hello world"
}'
```
输出为 JSON 数组。

### 6.4 查看模型详情（/api/show）

```bash
curl http://localhost:11434/api/show -d '{"model": "qwen2:7b"}'
```
返回模型的许可证、时间戳、模板、元数据等信息。

### 6.5 常用 API 端点

| 端点 | 方法 | 用途 |
|------|------|------|
| `/api/generate` | POST | 生成文本 |
| `/api/chat` | POST | 对话（支持上下文） |
| `/api/embeddings` | POST | 生成向量嵌入 |
| `/api/show` | POST | 查看模型详情 |
| `/api/pull` | POST | 下载模型 |
| `/api/delete` | DELETE | 删除模型 |


## 七、自定义模型（Modelfile）

### 7.1 创建 Modelfile

创建一个名为 `Modelfile` 的文件：
```
FROM qwen2:7b
SYSTEM """你是一位专业的采购专家顾问，擅长供应商管理和成本控制。
请用专业、简洁的方式回答问题。"""
PARAMETER temperature 0.7
PARAMETER num_ctx 4096
```

### 7.2 创建自定义模型

```bash
ollama create my-purchase-assistant -f Modelfile
```

### 7.3 运行自定义模型

```bash
ollama run my-purchase-assistant
```

**常用 Modelfile 参数**：

| 参数 | 作用 | 示例 |
|------|------|------|
| `FROM` | 指定基础模型 | `FROM qwen2:7b` |
| `SYSTEM` | 设置系统提示词 | `SYSTEM "你是一个助手"` |
| `PARAMETER temperature` | 控制随机性（0-1） | `PARAMETER temperature 0.7` |
| `PARAMETER num_ctx` | 上下文长度（tokens） | `PARAMETER num_ctx 4096` |


## 八、故障排除

### 8.1 查看日志

**Windows**：
- 日志目录：`%LOCALAPPDATA%\Ollama`（最新日志在 `server.log`）
- 二进制文件：`%LOCALAPPDATA%\Programs\Ollama`
- 模型存储：`%HOMEPATH%\.ollama`

**macOS**：
```bash
cat ~/.ollama/logs/server.log
```

**Linux（systemd）**：
```bash
journalctl -u ollama --no-pager --follow
```

**开启调试日志**：
```powershell
$env:OLLAMA_DEBUG="1" & "ollama app.exe"
```

### 8.2 常见问题及解决

| 问题 | 解决方案 |
|------|------|
| **模型未找到** | 最常见的错误。先用 `ollama pull 模型名` 下载 |
| **连接被拒绝** | 确认 Ollama 服务正在运行，默认端口是 11434 |
| **GPU 未被使用** | 检查 `ollama ps` 的 Processor 列是否显示 GPU |
| **推理速度慢** | 使用量化版本模型（如 `q4_k_m`），或调整 `OLLAMA_NUM_PARALLEL` |
| **GPU 发现失败** | 更新 GPU 驱动到最新版本；Linux 下检查 `nvidia-smi` 是否正常 |
| **显存不足** | 使用更小的模型或量化版本 |
| **Docker 中 GPU 失效** | 在 `/etc/docker/daemon.json` 中添加 `"exec-opts": ["native.cgroupdriver=cgroupfs"]` |

### 8.3 强制指定 LLM 库

Ollama 包含多个 LLM 库，如果自动检测有问题，可以强制指定：
```bash
OLLAMA_LLM_LIBRARY="cpu_avx2" ollama serve
```
可用的库：`cpu`、`cpu_avx`、`cpu_avx2`、`cuda_v11`、`rocm_v6`、`rocm_v7`。


## 九、环境变量配置

| 变量 | 作用 |
|------|------|
| `OLLAMA_MODELS` | 指定模型存储路径 |
| `OLLAMA_NUM_PARALLEL` | 限制并发请求数 |
| `OLLAMA_LLM_LIBRARY` | 强制指定 LLM 库 |
| `OLLAMA_TMPDIR` | 指定临时文件目录 |
| `OLLAMA_DEBUG` | 开启调试日志 |

**设置示例**（Windows）：
```powershell
setx OLLAMA_MODELS "D:\OllamaModels"
```


## 十、最佳实践

1. **模型存储路径**：安装后立即将模型存储路径从系统盘迁移到数据盘
2. **量化版本优先**：使用 `q4_k_m` 等量化版本，在精度损失极小的情况下大幅降低显存需求
3. **根据硬件选模型**：8GB 显存可运行 7B 模型，40GB 以上可运行 70B 模型
4. **限制并发**：通过 `OLLAMA_NUM_PARALLEL` 控制并发数，避免显存溢出
5. **使用标签管理版本**：用 `--tag` 参数标记不同版本（如 `qwen2:7b-v1.2`），便于后续管理
6. **生产环境安全**：建议启用 TLS 加密（配置 Nginx 反向代理）并设置访问白名单


## 十一、官方资源

| 资源 | 链接 |
|------|------|
| **官网** | https://ollama.com |
| **官方文档** | https://docs.ollama.com |
| **CLI 参考** | https://docs.ollama.com/cli |
| **API 参考** | https://docs.ollama.com/api |
| **GitHub** | https://github.com/ollama/ollama（169k+ Stars） |
| **模型库** | https://ollama.com/library |
| **Docker Hub** | https://hub.docker.com/r/ollama/ollama |

---

*文档版本：2026年8月 | 基于 Ollama 最新版本整理*