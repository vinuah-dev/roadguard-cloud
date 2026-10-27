#!/bin/bash
# Runs at every boot (roadguard.service): record this instance's metadata for the
# dashboard and Caddy, then build (cached) and start the docker compose stack.
set -euo pipefail
cd "$(dirname "$0")/../.."

IMDS=http://169.254.169.254/latest
TOKEN="$(curl -fsS -X PUT "$IMDS/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 300" || true)"
md() { curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" "$IMDS/meta-data/$1" 2>/dev/null || true; }

PUBLIC_IP="$(md public-ipv4)"
# sslip.io resolves a.b.c.d.sslip.io to a.b.c.d, which gives Caddy a host name to get a free certificate for.
PUBLIC_HOST=""
if [ -n "$PUBLIC_IP" ]; then
  PUBLIC_HOST="${PUBLIC_IP//./-}.sslip.io"
fi

cat > deploy/aws/instance.env <<EOF
ROADGUARD_PLATFORM=AWS EC2
ROADGUARD_REGION=$(md placement/region)
ROADGUARD_AZ=$(md placement/availability-zone)
ROADGUARD_INSTANCE_ID=$(md instance-id)
ROADGUARD_INSTANCE_TYPE=$(md instance-type)
ROADGUARD_PUBLIC_HOST=${PUBLIC_HOST}
SITE_ADDRESS=${PUBLIC_HOST:-localhost}
EOF

docker compose up -d --build --remove-orphans
docker image prune -f >/dev/null || true

echo "RoadGuard is up:  http://${PUBLIC_IP:-<public-ip>}   https://${PUBLIC_HOST:-<public-ip>.sslip.io}"
