# Executor for SpreadsheetBench's reference harness (driver.py): one IPython kernel per
# task, driven by kernel_server.py over stdin/stdout. Upstream's executor
# (code_exec_docker/Dockerfile.executor, SpreadsheetBench commit 49b73a9) is a Jupyter
# kernel gateway on quay.io/jupyter/datascience-notebook:2023-10-20; this image keeps that
# image's spreadsheet libraries (pandas 2.1, openpyxl 3.1) and leaves out the rest
# (torch, transformers, langchain, ...), which the tasks do not use.
#
#   docker build -t sbref-exec:1 benchmarks/reference/spreadsheetbench/exec
FROM python:3.11-slim
RUN pip install --no-cache-dir ipykernel==6.29.5 jupyter_client==8.6.3 \
    pandas==2.1.1 numpy==1.26.4 openpyxl==3.1.2 xlrd==2.0.1 && \
    useradd -u 1000 -m runner
COPY kernel_server.py /srv/kernel_server.py
USER 1000
ENV HOME=/tmp JUPYTER_RUNTIME_DIR=/tmp/jr IPYTHONDIR=/tmp/ipy MPLCONFIGDIR=/tmp/mpl
WORKDIR /tmp
CMD ["python", "/srv/kernel_server.py"]
