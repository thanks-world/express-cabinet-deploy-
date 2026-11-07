
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
