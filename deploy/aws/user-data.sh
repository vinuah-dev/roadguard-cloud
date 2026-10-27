#!/bin/bash
# =============================================================================
# RoadGuard - EC2 bootstrap. Paste this whole file into
#   EC2 > Launch instance > Advanced details > User data
# AMI: Amazon Linux 2023 (x86_64 or arm64), free-tier t3.micro / t2.micro.
#
# Installs Docker, clones the repository, generates the database password on the
# instance and starts PostgreSQL + the RoadGuard API + Caddy (HTTP/HTTPS).
# The first boot takes about 5-8 minutes (image build + model training).
# Progress: /var/log/roadguard-bootstrap.log, also visible in
#   EC2 > Instance > Actions > Monitor and troubleshoot > Get system log
# =============================================================================
set -euo pipefail
exec > >(tee -a /var/log/roadguard-bootstrap.log) 2>&1

REPO_URL="https://github.com/vinuah-dev/roadguard-cloud.git"
BRANCH="main"
APP_DIR="/opt/roadguard"
COMPOSE_VERSION="v2.29.7"
BUILDX_VERSION="v0.17.1"

echo "== RoadGuard bootstrap started $(date -u)"

# 1. Swap: 1 GB of RAM is tight for building the image next to PostgreSQL
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# 2. Docker engine + compose/buildx plugins + git
dnf install -y docker git
systemctl enable --now docker
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) BUILDX_ARCH=amd64 ;;
  aarch64) BUILDX_ARCH=arm64 ;;
  *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac
PLUGINS=/usr/local/lib/docker/cli-plugins
mkdir -p "$PLUGINS"
curl -fsSL -o "$PLUGINS/docker-compose" \
  "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-${ARCH}"
curl -fsSL -o "$PLUGINS/docker-buildx" \
  "https://github.com/docker/buildx/releases/download/${BUILDX_VERSION}/buildx-${BUILDX_VERSION}.linux-${BUILDX_ARCH}"
chmod +x "$PLUGINS/docker-compose" "$PLUGINS/docker-buildx"
docker compose version

# 3. Code
if [ ! -d "$APP_DIR/.git" ]; then
  git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi
cd "$APP_DIR"

# 4. Secrets are generated here and never leave the instance
if [ ! -f .env ]; then
  umask 077
  cat > .env <<EOF
POSTGRES_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
# Set a value to require an admin token for "Mark Repaired" and "Reset Test Data"
ROADGUARD_ADMIN_TOKEN=
ROADGUARD_ENABLE_TEST_API=true
EOF
  umask 022
fi

# 5. Start now and on every boot (start.sh refreshes the instance metadata first)
chmod +x deploy/aws/*.sh
install -m 644 deploy/aws/roadguard.service /etc/systemd/system/roadguard.service
systemctl daemon-reload
systemctl enable roadguard.service
systemctl start roadguard.service

echo "== RoadGuard bootstrap finished $(date -u)"
docker compose ps
