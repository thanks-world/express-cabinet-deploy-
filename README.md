# 冷藏柜远程监控与自动化运维系统 (Express Cabinet Deploy)

## 📖 项目简介

本项目是一个面向冷藏柜/快递柜场景的**全栈微服务系统**。项目不仅实现了从底层 Modbus TCP 通信、Web 端实时监控，更独立完成了一套**生产级容器化部署与可观测性闭环方案**。

项目通过 **Docker Compose** 管理底层容器，通过 **Ansible** 实现全套配置的**幂等渲染与自动化部署**，并最终使用 **Prometheus + Alertmanager** 实现了从基础设施到业务温度的端到端告警。

---

## 🏗️ 系统架构与功能拆解

项目包含三个独立且互联的技术栈，共享同一个外部网络 `cabinet-net`：

### 1. 业务栈 (Modbus Stack)
- **Software A (服务端)**：启动 Modbus TCP 服务（端口 `5020`）和 HTTP API 服务。内置制冷逻辑与抽屉控制，负责向 Prometheus 暴露业务指标。
- **Software B (客户端)**：通过环境变量 `MODBUS_HOST` 连接服务端，周期性拉取实时温度并在终端打印监控日志。

### 2. Web 栈 (Web Stack)
- **Gateway (网关)**：基于 Java 编写的 HTTP 网关，接收前端请求并转发至 Modbus 服务端。
- **Nginx**：托管静态前端页面（包含 ECharts 温度曲线），并将 API 请求反向代理至 Gateway。
- **Redis**：提供缓存支持。

### 3. 监控栈 (Monitoring Stack)
- **Prometheus**：核心时序数据库，通过 `scrape_interval` 拉取各类指标。
- **Alertmanager**：接收 Prometheus 告警，并按路由规则处理。
- **Exporters**：
  - `node-exporter`：采集宿主机 CPU/内存/磁盘指标。
  - `nginx-exporter`：采集 Nginx 的 `stub_status` 连通性指标。
  - `blackbox-exporter`：对 HTTP 服务进行主动探活（黑盒测试）。
  - `cabinet-exporter`：自定义 Python 服务，将冷藏柜业务温度转化为 Prometheus 指标（`cabinet_temperature_celsius`）。

---

## 💡 核心工程决策与取舍

1. **多阶段构建镜像**：
   使用 `maven:3.9-eclipse-temurin-17` 进行编译，将产物移交至 `jre-jammy` 运行，有效减小了业务容器体积，提升了启动速度与安全性。

2. **健康检查解决启动时序依赖**：
   `Software B` 依赖 `Software A` 的 Modbus 服务，但容器启动不保证应用就绪。通过为 A 编写 `CMD-SHELL` 类型的 `healthcheck`（探测 `/dev/tcp/127.0.0.1/5020`），并在 B 的 `depends_on` 中声明 `condition: service_healthy`，彻底消除了 `Connection refused` 的竞态问题。

3. **配置与代码解耦**：
   客户端代码中的连接地址不再硬编码为 `127.0.0.1`，而是通过 `System.getenv()` 读取 `MODBUS_HOST`。使得同一份镜像可以无缝适配单机运行与容器网络通信。

4. **Ansible 幂等部署与变更重载**：
   不使用 Ansible 去构建镜像（避免破坏宿主机环境依赖），Ansible 仅负责**渲染配置**与**编排容器**。通过 `register` 记录模板变化，结合 `when: prom_cfg.changed` 触发特定容器的重启，实现了“无变更不重建”的严格幂等。

---

## 🚀 快速开始 (一键部署)

### 前提条件
- 宿主机已安装 Docker, Docker Compose (V2) 与 Ansible。
- 位于项目根目录 `/root/express-cabinet-deploy`。

### 执行部署
```bash
# 进入 Ansible 目录
cd deploy/ansible

# 执行 Playbook
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run.log
