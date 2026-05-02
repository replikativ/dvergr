# Alternative: standalone Dockerfile for dvergr-openclaw image
#
# The preferred approach is to use OpenClaw's built-in OPENCLAW_DOCKER_APT_PACKAGES
# build arg (see dev.sh cmd_build), which installs Java in one layer.
#
# This Dockerfile exists as a fallback if you already have an openclaw:local
# image without Java and want to layer it on top:
#
#   docker build -t dvergr-openclaw:dev -f docker/openclaw/Dockerfile .

FROM openclaw:local

USER root

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

USER node
