from python:3.12-slim

workdir /app
run pip install --no-cache-dir flask==3.0.3 requests==2.31.0

copy cabinet_exporter.py . 
expose 9101
cmd ["python", "cabinet_exporter.py"]
