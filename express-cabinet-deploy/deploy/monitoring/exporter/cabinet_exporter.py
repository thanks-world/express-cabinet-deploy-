from flask import Flask, Response, request
import requests

app = Flask(__name__)

state = {"temperature": 6.5}

@app.route("/metrics")
def metrics():
    try:
        resp = requests.get("http://host.docker.internal:8090/api/temp", timeout=1)
        if resp.status_code == 200:
            data = resp.json()
            temp = float(data.get("currentTemp", state["temperature"]))
            state["temperature"] = temp
    except Exception: 
        pass

    temp = state["temperature"]
    high = 1 if temp > 8 else 0
    low = 1 if temp < 2 else 0

    body = f"""# HELP cabinet_temperature_celsius 冷藏柜抽屉温度
# TYPE cabinet_temperature_celsius gauge
cabinet_temperature_celsius{{drawer="1"}} {temp}
# HELP cabinet_alarm_active 高低温告警状态 1=告警
# TYPE cabinet_alarm_active gauge
cabinet_alarm_active{{type="high"}} {high}
cabinet_alarm_active{{type="low"}} {low}
"""

    return Response(body, mimetype="text/plain; version=0.0.4")

@app.route("/simulate", methods=["POST", "GET"])
def simulate():
    temp = float(request.args.get("temp", "6.5"))
    state["temperature"] = temp
    return {"temperature": temp}

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9101)

