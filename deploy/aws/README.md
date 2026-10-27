# Deploying RoadGuard on AWS (Free Tier)

One EC2 instance runs the whole stack with Docker Compose:

```
Internet ──► EC2 t3.micro (Amazon Linux 2023, ap-south-1)
              ├─ caddy     :80 / :443  reverse proxy, free HTTPS certificate (Let's Encrypt via sslip.io)
              ├─ app       :8000       FastAPI + Uvicorn, Random Forest, WebSocket /ws/live
              └─ db        :5432       PostgreSQL 16 (data on the instance's EBS volume)
```

Everything is set up by [`user-data.sh`](user-data.sh) on first boot. No SSH key, local Docker or AWS CLI is needed.

## Free tier: what it costs

| Account created | What applies | This deployment |
| :--- | :--- | :--- |
| On/after 15 July 2025 (**Free plan**) | Up to US$200 credits, valid 6 months; EC2, EBS and public IPv4 usage is paid from the credits | t3.micro (~US$8) + 20 GB gp3 (~US$2) + 1 public IPv4 (~US$3.6) is roughly US$14/month, well inside the credits |
| Before 15 July 2025 | 12-month free tier: 750 h/month of t2.micro or t3.micro, 30 GB EBS, 750 h of public IPv4 | US$0 while inside those limits |

Check **Billing → Free tier** in the console for your account's exact numbers, and create a **zero-spend budget** (Billing → Budgets) so AWS emails you before anything is charged.

## Launch (console, about 3 minutes + 6 minutes of setup)

1. Region **Asia Pacific (Mumbai) ap-south-1** (top-right of the console).
2. **EC2 → Instances → Launch instances**
   - **Name:** `roadguard`
   - **AMI:** Amazon Linux 2023 (Free tier eligible), 64-bit (x86)
   - **Instance type:** `t3.micro` (Free tier eligible)
   - **Key pair:** *Proceed without a key pair* (you can still open a browser shell with EC2 Instance Connect if you allow SSH later)
   - **Network settings:** create a security group, tick **Allow HTTPS traffic** and **Allow HTTP traffic from the internet**; untick SSH
   - **Storage:** 20 GiB gp3
   - **Advanced details → User data:** paste the whole of `deploy/aws/user-data.sh`
3. **Launch instance**, open it, copy the **Public IPv4 address**.
4. Wait about 6 minutes (Docker install, image build, model training), then open
   - `http://<public-ip>/`
   - `https://<public-ip-with-dashes>.sslip.io/` (for example `https://13-233-10-20.sslip.io/`)
   - API docs: `/docs`, health: `/health`

Progress is in **Actions → Monitor and troubleshoot → Get system log** (lines starting with `== RoadGuard`).

## Operating it

Open a shell with **Connect → EC2 Instance Connect** (needs inbound SSH 22 from the EC2 Instance Connect range in the security group) and use:

```bash
cd /opt/roadguard
sudo docker compose ps                       # container status
sudo docker compose logs -f app              # API logs
sudo ./deploy/aws/update.sh                  # pull the latest code from GitHub and redeploy
sudo docker compose exec db pg_dump -U roadguard roadguard > backup.sql   # database backup
```

* **Admin token:** edit `/opt/roadguard/.env`, set `ROADGUARD_ADMIN_TOKEN=<something long>`, then `sudo ./deploy/aws/start.sh`. "Mark Repaired" and "Reset Test Data" will then ask for it.
* **Stopping saves money** but the public IP changes on the next start. `start.sh` runs at every boot and picks up the new IP and sslip.io name automatically.
* **Amazon RDS (optional):** create a PostgreSQL free-tier instance in the same VPC, allow port 5432 from the EC2 security group, add `DATABASE_URL=postgresql://user:pass@<rds-endpoint>:5432/postgres` to `.env` and run `sudo ./deploy/aws/start.sh`.
* **Remove everything:** terminate the instance (its EBS volume is deleted with it) and delete the security group.
