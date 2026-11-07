'''快速开始
# 进入 Ansible 目录
cd deploy/ansible

# 执行 Playbook
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run.log
'''

# 冷藏柜远程监控与自动化运维系统 (Express Cabinet Deploy)

本项目是一个基于 Modbus TCP 的冷藏柜/快递柜远程监控课程设计项目。系统不仅包含底层 Modbus 通信与 Web 端实时展示，更独立扩展了一套**基于 Ansible 的自动化部署方案**与**Prometheus + Alertmanager 的可观测性闭环体系**。

> **说明**：本项目为两人协作课程设计。本人独立负责 Web 端全部设计开发、Nginx 部署，并独立完成了整套微服务容器化规划、Ansible 编排与监控告警体系搭建。监控扩展属于超出课设原始要求的“可观测性补强”。

## 1. 系统架构概览

系统由三个独立互联的技术栈组成，共享外部网络 `cabinet-net`：

- **业务栈 (Modbus)**：`Software A`（服务端，暴露 5020/8090 端口），`Software B`（客户端，拉取实时数据）。
- **Web 栈**：`Gateway`（业务网关）、`Nginx`（静态页面与反向代理）、`Redis`（缓存）。
- **监控栈**：`Prometheus`、`Alertmanager`、业务及基础设施 `Exporters`。

### 监控数据流向：
浏览器 ↔ Nginx (80) ↔ Gateway (8080) ↔ Modbus (5020)
  │
  ├─ Prometheus (9090) 抓取指标
  │    ├─ cabinet-exporter (9101)  # 业务温度指标
  │    ├─ node-exporter (9100)     # 宿主机指标
  │    ├─ nginx-exporter (9113)    # Nginx 探活
  │    └─ blackbox-exporter (9115) # HTTP 黑盒探测
  │
  └─ Alertmanager (9093) 接收告警并路由

## 2. 核心工程实践与取舍

### 2.1 容器化与多阶段构建
编写 `Dockerfile` 将 Java 源码进行多阶段编译（Maven → JRE），剥离构建环境，使业务运行镜像体积大幅减小。

### 2.2 解决启动时序竞态 (Healthcheck)
`Software B` 依赖 `Software A` 的 5020 端口，但容器启动不等于服务就绪。通过为 A 配置 `CMD-SHELL` 健康检查探测 `/dev/tcp/127.0.0.1/5020`，并在 B 的 `depends_on` 中声明 `condition: service_healthy`，彻底消除了客户端启动过早导致的 `Connection refused`。

### 2.3 配置与代码解耦
业务代码中不硬编码 `127.0.0.1`，改为通过 `System.getenv()` 读取 `MODBUS_HOST`。使得同一份镜像可灵活适配单机调试与 Docker 网络通信。

### 2.4 Ansible 幂等编排与变更重载
不使用 Ansible 直接操作宿主机编译，而是通过模板渲染（`.j2`）生成配置，并编排 `docker compose`。利用 `register` 记录模板变更，结合 `when: prom_cfg.changed` 触发特定容器重启，实现“无变更不重启”的严格幂等。

## 3. 排错实录 (Troubleshooting)

项目在落地过程中解决了多个极具代表性的运维问题：

1. **`node-exporter` 无限重启**
   - *现象*：容器持续 `Restarting`，日志报 `open /host/proc/devices/system/cpu/isolated: not a directory`。
   - *解决*：修正挂载路径，将 `--path.sysfs` 从 `/host/proc` 改为 `/host/sys`。
2. **Ansible 覆盖配置但容器未更新**
   - *现象*：修改了 `.j2` 模板，跑完 Playbook 后容器内文件依然是旧的。
   - *解决*：`docker_compose_v2` 在 `recreate: auto` 策略下不检测挂载文件内容变化。改为在模板渲染任务上使用 `register`，状态为 `changed` 时显式重启对应服务。
3. **Prometheus 告警规则始终 `inactive`**
   - *现象*：温度已模拟至 9℃，但告警不触发。
   - *排查*：通过 `/api/v1/rules` 发现表达式指标名被误拼为 `cabinet_temperature_cesius`（少了一个 `l`）。
   - *解决*：修正 Jinja2 模板中的拼写并重载，告警状态成功跃迁至 `firing`。

## 4. 快速开始

**环境要求**：Docker / Docker Compose V2 / Ansible (core 2.16+)。

```bash
# 1. 进入 Ansible 目录
cd deploy/ansible

# 2. 一键部署三大栈（11 个容器）
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run1.log

# 3. 幂等验证（再次执行，预期 changed=0）
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run2.log
5. 验证入口与证据链
Prometheus Targets：http://<服务器IP>:9090/targets（应包含 5 个全绿的 Job）

Alertmanager UI：http://<服务器IP>:9093

温度模拟 API：curl -X POST 'http://localhost:9101/simulate?temp=9'

完整的测试验证步骤见 docs/TEST_GUIDE.md。所有系统运行状态、健康检查与告警触发日志均保存在 docs/evidence/ 目录中，作为项目交付的硬证据。

6. 已知限制
单机课设项目，非生产级高可用部署。

客户端采用 3 秒轮询拉取，生产环境下应升级为 SSE 或 WebSocket 以应对高并发。

text

---

### 📄 2. `docs/TEST_GUIDE.md`

```markdown
# 测试引导与检测清单 (Test Guide & Acceptance Criteria)

> 适用：`express-cabinet-deploy` 全栈系统与 Ansible 部署  
> 目标：用可重复的命令验证三个栈的连通性、监控指标及告警闭环。

## 1. 环境预检与部署幂等性

```bash
# 检查依赖
docker --version && ansible --version

# 首次部署
cd deploy/ansible
ansible-playbook site.yml | tee ../../docs/evidence/ansible-cold-boot.log

# 幂等验证
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run2.log
通过标准：

首次部署或冷启动后，PLAY RECAP 的 failed=0。

第二次执行时，PLAY RECAP 的 changed=0，failed=0。

2. 基础架构与状态检测
bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
通过标准：

11 个容器全部处于 Up 状态。

cabinet-software-a 状态显示为 Up (healthy)。

3. 监控目标 (Targets) 检测
bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'
通过标准：
返回结果包含 5 个 Job：blackbox-http, cabinet-exporter, nginx, node, prometheus，且全部为 "health": "up"。

4. 业务指标与告警闭环检测
4.1 模拟高温
bash
curl -X POST 'http://localhost:9101/simulate?temp=9'
sleep 45  # 必须等待，以满足 Prometheus 配置的 for: 30s 持续时间
4.2 Prometheus 状态检测
bash
curl -s http://localhost:9090/api/v1/alerts | grep CabinetTemperatureHigh
通过标准：输出中应包含 "state":"firing"。

4.3 Alertmanager 接收检测
bash
curl -s http://localhost:9093/api/v2/alerts | jq .
通过标准：返回 JSON 中包含该告警的详细信息，且 "status": {"state": "active"}。

4.4 告警恢复
bash
curl -X POST 'http://localhost:9101/simulate?temp=6.5'
sleep 45
# 再次查询 Prometheus 和 Alertmanager，告警应消失或变为 resolved
5. 探活黑盒测试 (Blackbox)
5.1 Nginx 故障注入
bash
docker stop cabinet-nginx
sleep 60
curl -s http://localhost:9090/api/v1/alerts | grep -E "ApiProbeDown|NginxTargetDown"
通过标准：出现探活失败告警。

5.2 恢复 Nginx
bash
docker start cabinet-nginx
sleep 60
curl -s http://localhost:9090/api/v1/alerts | grep -E "ApiProbeDown|NginxTargetDown"
通过标准：告警恢复（不再输出该告警项）。

6. 日志与配置渲染检测
bash
# 检查是否有残留的未渲染变量
grep -R "{{" deploy/monitoring/prometheus deploy/monitoring/alertmanager

# 检查 Prometheus 配置加载日志
docker logs cabinet-prometheus 2>&1 | grep -i "error loading config"
通过标准：

grep 无输出（表示所有 .j2 变量均已被 Ansible 正确渲染）。

容器日志中无配置加载报错。

7. 验收清单
检查项	检查命令 / 位置	通过标准
冷启动	ansible-playbook site.yml	failed=0
严格幂等	再次执行 Playbook	changed=0
容器状态	docker ps	11 个容器全 Up
Target 探活	/api/v1/targets	5 个 Job 全 up
业务指标	curl :9101/metrics	包含 cabinet_temperature_celsius
高温告警	模拟 temp=9	Prometheus firing，AM 接收 active
探活告警	docker stop cabinet-nginx	Prometheus 触发 ApiProbeDown
配置渲染	grep -R "{{"	无未渲染变量泄漏
8. 常见问题排查 (FAQ)
Q: node-exporter 报 isolated cpus 错误？

A: 检查 docker-compose.yml 中的 --path.sysfs 路径，必须为 /host/sys。

Q: 告警表达式显示 inactive 且不触发？

A: 检查 /api/v1/rules 中的指标名拼写是否与 /api/v1/query 查询到的指标名完全一致。

Q: docker_compose_v2 提示找不到 community.docker？

A: 确保安装的版本兼容，例如 ansible-galaxy collection install community.docker:3.13.3。



before  ---  README`s document
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
注：首次运行会编译业务镜像并拉起所有容器，耗时较长。

幂等性验证
再次执行相同命令：

bash
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run2.log
预期输出：PLAY RECAP 中 changed=0 failed=0，证明系统已达到期望状态。

📊 告警闭环验证
监控栈部署完成后，按以下步骤验证端到端的告警流转：

触发高温告警（模拟温度升至 9℃，超过 8℃ 阈值）：

bash
curl -X POST 'http://localhost:9101/simulate?temp=9'
等待 30 秒（等待 Prometheus 抓取并满足 for: 30s 持续时间）。

验证 Prometheus 触发：

bash
curl -s http://localhost:9090/api/v1/alerts | grep CabinetTemperatureHigh
# 预期结果：state 为 "firing"
验证 Alertmanager 接收：

bash
curl -s http://localhost:9093/api/v2/alerts | jq .
# 预期结果：返回包含 "status": "active" 的告警 JSON
恢复温度：

bash
curl -X POST 'http://localhost:9101/simulate?temp=6.5'
📝 排错实录 (Troubleshooting)
在构建过程中，解决了多个具有代表性的工程问题：

node-exporter 无限重启（Panic 崩溃）
现象：容器持续处于 Restarting。
排查：查看日志报错 open /host/proc/devices/system/cpu/isolated: not a directory。
解决：在 docker-compose.yml 中，将 --path.sysfs 指向的路径从 /host/proc 修正为真实的 /host/sys。同时确保镜像版本与 --collector.filesystem 排除参数兼容。

Prometheus 告警始终 inactive
现象：高温触发后，/api/v1/alerts 无数据。
排查：通过 curl http://localhost:9090/api/v1/rules 发现底层表达式指标名拼写为 cabinet_temperature_cesius（少了一个 l）。通过 curl /api/v1/query 确认了真实指标名为 cabinet_temperature_celsius。
解决：修正 Ansible 模板 alerts.yml.j2，依靠 Playbook 的 register + when 机制自动重载了 Prometheus 配置。

Ansible 模板渲染覆盖导致容器不更新
现象：修改了 prometheus.yml.j2，但跑完 Ansible 容器内配置未生效。
排查：docker_compose_v2 模块在 recreate: auto 策略下，只对比容器本身的属性，不检测挂载卷内的文件内容。
解决：将配置渲染任务注册为变量（register: prom_cfg），若 prom_cfg.changed 为真，则显式调用 docker compose 重启对应服务。

📂 证据链留存
项目的所有验证过程均留有硬证据，存放于 docs/evidence/：

部署证据：ansible-run.log (首次部署), ansible-run2.log (幂等验证 changed=0), ansible-cold-boot.log (冷启动拉起)。

运行状态：docker-ps-all.log (11个容器全部 Up，A 为 healthy)。

监控指标：prometheus-targets.log (5个 target 全 UP)，prometheus-alertmanagers.log (Alertmanager 关联成功)。

告警闭环：prometheus-alert-firing.log (Prometheus 状态 Firing)，alertmanager-active-alerts.log (Alertmanager 收到 Active 告警)。
