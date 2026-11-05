# express-cabinet-deploy

基于 Modbus TCP 的冷藏柜/快递柜远程监控课设项目，包含软件 A（服务端）、软件 B（客户端）、Nginx Web 入口，以及本人独立扩展的 Prometheus + Alertmanager 监控栈与 Ansible 部署。

> 说明：本项目为两人协作课程设计。本人独立负责 Web 端全部设计、开发与 Nginx 部署，并独立完成监控栈与 Ansible 自动化扩展。监控扩展非课设原始需求，属于“可观测性补强”。

## 1. 项目背景与目标

项目模拟一组冷藏柜抽屉的温控与开关控制：

- 软件 A 作为 Modbus TCP 服务端，维护温度、抽屉状态、密码锁等寄存器。
- 软件 B 作为客户端，轮询服务端并展示实时温度、抽屉状态，支持远程设温、开关抽屉、设置密码。
- Nginx 提供 Web 入口与 `/healthz`、`/stub_status` 探活端点。
- 监控栈通过 exporter 将业务温度指标暴露给 Prometheus，配置阈值告警并路由到 Alertmanager。

## 2. 我的角色与交付

- Web 端：页面结构、交互逻辑、接口联调、Nginx 配置与容器化部署。
- 监控扩展：Flask exporter、Prometheus 抓取与告警规则、Alertmanager 路由、Blackbox 探活、Nginx exporter。
- 自动化：Ansible playbook 模板化渲染 Prometheus、Alertmanager、docker-compose，并完成健康检查。
- 证据沉淀：`docs/evidence/` 下保存 playbook 日志、容器状态、告警触发与恢复记录。

## 3. 架构概览

```text
浏览器
  │
  ▼
Nginx (cabinet-nginx, :80)
  ├─ /healthz
  ├─ /stub_status
  └─ 反向代理到 Web 后端

Prometheus (:9090)
  ├─ scrape cabinet-exporter :9101   # 业务温度指标
  ├─ scrape node-exporter   :9100   # 主机指标
  ├─ scrape nginx-exporter  :9113   # Nginx stub_status
  └─ scrape blackbox        :9115   # HTTP 探活

Alertmanager (:9093)
  └─ 接收 Prometheus 告警，按 route 路由

Ansible
  └─ 渲染配置 → docker compose up → 健康检查 → 告警规则校验
