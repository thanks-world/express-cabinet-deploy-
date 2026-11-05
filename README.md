# Express Cabinet Monitoring System (快递柜温度监控系统)

本项目是一个基于 Java 开发的工业级快递柜模拟终端与监控系统。项目核心使用 Modbus TCP 协议模拟本地硬件通信（温度、压缩机、抽屉状态），并集成 Prometheus + Alertmanager 构建了完整的可观测性告警体系。同时提供 Ansible 实现一键自动化部署。

## 🚀 核心功能

- **核心业务 (Java)**：
  - 基于 Modbus TCP 模拟快递柜底层通信（监听 `5020` 端口）。
  - 模拟 10 个抽屉的独立温控、开关状态与密码鉴权。
  - 内置轻量级 HTTP API 服务（监听 `8090` 端口），提供 JSON 格式的业务数据。
  - **双模式运行**：支持本地带图形界面（GUI）运行，也支持 Linux 服务器无头终端（Headless）运行。
- **可观测性 (Prometheus)**：
  - 使用 Prometheus 抓取业务指标与 Nginx 状态。
  - 配置 Alertmanager 实现温度超限、服务宕机的邮件/Webhook 告警。
- **自动化部署 (Ansible)**：
  - 使用 Ansible 剧本实现监控栈的模板渲染与容器化一键拉起。

## 🛠 技术栈

- **语言/通信**：Java 17, Modbus TCP, HTTP Server
- **监控/告警**：Prometheus, Alertmanager, Blackbox Exporter
- **自动化/容器**：Ansible, Docker, Docker Compose
- **Web 服务**：Nginx, Redis (提供辅助 Web 端展示，非核心业务依赖)

## 📂 项目结构

```text
.
├── src/main/java/        # 核心业务代码 (SoftwareA, SoftwareB, ModbusServer)
├── deploy/
│   ├── monitoring/       # Prometheus / Alertmanager 监控栈配置
│   └── ansible/          # Ansible 自动化部署剧本 (site.yml)
├── web/                  # Nginx, Redis, Gateway 等辅助 Web 栈 (Docker Compose)
├── docs/evidence/        # 验收截图与日志证据
└── pom.xml               # Maven 构建文件
💻 快速开始 (纯终端模式)
1. 环境要求
JDK 17+

Maven 3.9+

Docker & Docker Compose (用于监控栈)

2. 编译项目
在项目根目录下执行：

bash
mvn clean package -DskipTests
3. 运行核心业务 (无桌面环境)
注意：服务器无图形界面时，必须加上 --headless 参数，否则会抛出 HeadlessException。

启动服务端 (SoftwareA)，保持终端不关闭：

bash
java -cp "target/classes:target/lib/*" SoftwareA --headless
新开一个终端，启动客户端轮询监控 (SoftwareB)：

bash
cd /root/express-cabinet-deploy
java -cp "target/classes:target/lib/*" SoftwareB --headless
(如果你在本地 Windows/Mac 上，去掉 --headless 参数即可启动图形界面)


