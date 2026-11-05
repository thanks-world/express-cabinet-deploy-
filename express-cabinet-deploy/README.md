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
4. 目录结构
text
express-cabinet-deploy/
├── cabinet/
|   ├── src/                         # 软件 A / B Java 源码
|   └── web/                         # Web 端与 Nginx 配置
├── deploy/
│   ├── monitoring/              # Prometheus / Alertmanager / exporter
│   │   ├── docker-compose.yml
│   │   ├── prometheus/
│   │   ├── alertmanager/
│   │   ├── blackbox/
│   │   └── exporter/
│   └── ansible/                 # 监控栈自动化部署
│       ├── site.yml
│       ├── group_vars/all.yml
│       └── templates/
└── docs/
    ├── TEST_GUIDE.md            # 测试引导与检测清单
    └── evidence/                # 运行日志、截图、告警记录
5. 关键设计与取舍
5.1 密码为什么用 RSA 而不是哈希
抽屉密码需要被服务端验证原文，且客户端不应明文传输。采用 RSA 公钥加密、服务端私钥解密后校验，避免校园网环境下明文密码被截获。代价是公钥分发与密钥管理复杂度上升，生产环境应配合 TLS 与密钥轮换。

5.2 为什么用 3 秒轮询而不是 WebSocket
课设场景下客户端数量少，3 秒轮询实现简单、易调试，能接受的实时性足够。代价是并发上升时后端压力增大；生产环境应升级为 SSE 或 WebSocket。

5.3 业务告警与运维告警的区分
业务告警：软件 A 内部的高低温告警，是业务功能。

运维告警：Prometheus 基于 exporter 指标配置的 CabinetTemperatureHigh、ApiProbeDown、NginxTargetDown，属于可观测性。

两者通过 cabinet-exporter 打通：exporter 暴露业务温度指标，Prometheus 负责抓取、判定与路由，概念上不混为一谈。

5.4 为什么用 Ansible 管理监控栈
手动 docker compose up 容易产生配置漂移。Ansible 模板化渲染 Prometheus 抓取配置、告警规则、Alertmanager 路由和 compose 文件，保证重复部署一致，并通过 uri 任务做健康检查。

5.5 node-exporter 的路径取舍
node-exporter 必须使用：

yaml
- "--path.procfs=/host/proc"
- "--path.sysfs=/host/sys"
若把 --path.sysfs 指向 /host/proc，会读取 /host/proc/devices/system/cpu/isolated 失败并 panic。该问题已在排障文档中记录。

6. 快速开始
6.1 启动 Web 与 Nginx
bash
cd /root/express-cabinet-deploy/web
docker compose up -d
curl -i http://localhost/healthz
curl -s http://localhost/stub_status
6.2 启动监控栈（手写 compose）
bash
cd /root/express-cabinet-deploy/deploy/monitoring
docker compose config >/dev/null && echo "YAML OK"
docker compose up -d
docker compose ps
6.3 使用 Ansible 部署监控栈
bash
sudo dnf install -y python3-requests
cd /root/express-cabinet-deploy/deploy/ansible
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run1.log
7. 验证入口
Prometheus：http://localhost:9090/targets

Alertmanager：http://localhost:9093

业务指标：curl -s http://localhost:9101/metrics

温度模拟：curl -X POST 'http://localhost:9101/simulate?temp=9'

告警查询：curl -s http://localhost:9090/api/v1/alerts

Alertmanager 告警：curl -s http://localhost:9093/api/v2/alerts | jq .

完整测试步骤见 docs/TEST_GUIDE.md。

8. 证据与限制
证据保存在 docs/evidence/：

ansible-run1.log：playbook 首次跑通，failed=0

ansible-run2.log：幂等验证日志

docker-compose-ps.txt：容器全部 Up

Prometheus /targets 全部 UP 截图

CabinetTemperatureHigh 为 firing 的截图

Alertmanager active 告警 JSON

已知限制：

单机课设项目，不是生产级高可用部署。

轮询在大量并发下不适用，生产应升级 SSE/WebSocket。

docker_compose_v2 的 build: always 会导致非严格幂等；如需展示 changed=0，改为 build: policy。

Ansible 使用 community.docker 3.13.3 以兼容 ansible-core 2.16。

text

---

## 2. `docs/TEST_GUIDE.md`

```markdown
# 测试引导与检测清单

> 适用：express-cabinet-deploy 监控栈与 Ansible 部署  
> 目标：用可重复命令验证 Web、Prometheus、Alertmanager、exporter 与告警闭环。

## 1. 环境预检

```bash
docker --version
docker compose version
ansible --version
python3 -c "import requests; print('requests OK')"
ss -tlnp | grep -E ':(80|9090|9093|9100|9101|9113|9115)\b'
预期：

Docker 与 compose 可用。

ansible-core 可用。

requests 可导入。

端口未被意外占用。

2. 手写 compose 校验
bash
cd /root/express-cabinet-deploy/deploy/monitoring
docker compose config >/dev/null && echo "YAML OK"
docker compose up -d
docker compose ps
检测点：

bash
docker logs --tail 20 cabinet-node-exporter
curl -s http://localhost:9100/metrics | head
curl -s http://localhost:9101/metrics | head
curl -s http://localhost:9113/metrics | head
curl -s http://localhost:9090/-/ready
curl -s http://localhost:9093/-/ready
通过标准：

cabinet-node-exporter 状态为 Up，日志无 panic: ... isolated cpus。

三个 exporter 的 /metrics 均有返回。

Prometheus 与 Alertmanager ready 返回 200。

3. Ansible 部署与幂等检测
3.1 首次运行
bash
cd /root/express-cabinet-deploy/deploy/ansible
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run1.log
通过标准：

text
PLAY RECAP
localhost : ok=... changed=... unreachable=0 failed=0
3.2 幂等验证
先调整 site.yml 中“启动监控站”任务，避免 build: always 每次重建：

yaml
build: policy
再跑第二次：

bash
ansible-playbook site.yml | tee ../../docs/evidence/ansible-run2.log
通过标准：changed=0，failed=0。

3.3 Handler 验证
bash
ansible-playbook site.yml -e "scrape_interval=30s" | tee ../../docs/evidence/ansible-handler.log
检测点：修改 prometheus.yml.j2 后应触发 重启 prometheus handler。

4. Prometheus Targets 检测
bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'
通过标准：node-exporter、nginx、cabinet-exporter、blackbox-http 等 job 全部 health: "up"。同时打开 http://localhost:9090/targets 截图保存。

5. 业务指标与温度模拟
bash
curl -s http://localhost:9101/metrics | grep cabinet_temperature_celsius
curl -X POST 'http://localhost:9101/simulate?temp=9'
curl -s http://localhost:9101/metrics | grep cabinet_temperature_celsius
通过标准：温度从 6.5 变为 9.0，cabinet_alarm_active{type="high"} 变为 1。

6. 告警触发与恢复检测
6.1 触发高温告警
bash
curl -X POST 'http://localhost:9101/simulate?temp=9'
sleep 45
curl -s http://localhost:9090/api/v1/alerts | grep -A 3 CabinetTemperatureHigh
curl -s http://localhost:9093/api/v2/alerts | jq .
通过标准：

Prometheus 中 CabinetTemperatureHigh 状态为 firing。

Alertmanager /api/v2/alerts 返回 active 告警，包含 drawer="1"、severity="warning"。

6.2 恢复
bash
curl -X POST 'http://localhost:9101/simulate?temp=6.5'
sleep 60
curl -s http://localhost:9090/api/v1/alerts | grep CabinetTemperatureHigh
curl -s http://localhost:9093/api/v2/alerts | jq .
通过标准：告警不再 firing，Alertmanager 中对应告警进入 resolved 或消失。

7. 探活与故障注入
7.1 Nginx 探活
bash
curl -i http://localhost/healthz
curl -s http://localhost/stub_status
7.2 停止 Nginx 触发探活告警
bash
docker stop cabinet-nginx
sleep 60
curl -s http://localhost:9090/api/v1/alerts | grep -E "ApiProbeDown|NginxTargetDown"
通过标准：出现 ApiProbeDown 或 NginxTargetDown。

7.3 恢复 Nginx
bash
docker start cabinet-nginx
sleep 60
curl -s http://localhost:9090/api/v1/alerts | grep -E "ApiProbeDown|NginxTargetDown"
通过标准：告警恢复。

8. 日志与配置检测
bash
docker logs --tail 50 cabinet-prometheus
docker logs --tail 50 cabinet-alertmanager
docker logs --tail 50 cabinet-cabinet-exporter
docker logs --tail 50 cabinet-blackbox-exporter
grep -R "{{" deploy/monitoring/prometheus deploy/monitoring/alertmanager
通过标准：无未渲染的 {{ ... }}；Prometheus 规则加载无报错；Alertmanager 配置加载成功。

9. 证据保存清单
保存到 docs/evidence/：

ansible-run1.log、ansible-run2.log、ansible-handler.log

docker-compose-ps.txt

prometheus-targets.png

prometheus-alerts-firing.png

alertmanager-alerts.json

nginx-down-alert.png

nginx-recovered.png

10. 验收清单
检查项	命令/位置	通过标准
compose YAML 合法	docker compose config	无报错
node-exporter 稳定	docker compose ps	Up，无 Restarting
Prometheus ready	curl :9090/-/ready	200
Alertmanager ready	curl :9093/-/ready	200
业务指标存在	curl :9101/metrics	含 cabinet_temperature_celsius
温度告警触发	simulate?temp=9 + /alerts	firing
温度告警恢复	simulate?temp=6.5	不再 firing
Nginx 探活告警	docker stop cabinet-nginx	ApiProbeDown/NginxTargetDown
Ansible 首次跑通	ansible-run1.log	failed=0
Ansible 幂等	ansible-run2.log	changed=0、failed=0
无未渲染变量	grep -R "{{"	监控配置中无残留
11. 常见问题
Unable to get isolated cpus：检查 --path.sysfs=/host/sys。

No module named 'requests'：sudo dnf install -y python3-requests。

community.dockers：命名空间应为 community.docker。

handler 找不到：notify 与 handler 名称大小写必须一致。

changed=1 来自 build: always：改为 build: policy 后再做幂等验证。
