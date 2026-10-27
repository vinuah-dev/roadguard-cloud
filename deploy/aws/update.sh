#!/bin/bash
# Pull the latest code from GitHub and redeploy (data in PostgreSQL is kept):
#   sudo /opt/roadguard/deploy/aws/update.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
git pull --ff-only
exec ./deploy/aws/start.sh
