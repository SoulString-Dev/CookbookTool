# 把同步服务端装到 NAS 上

一句话：在开发机上把服务端打成镜像（或胖包），拷到 NAS 上跑起来，然后在手机 App 的
「设置 → 家庭共享」里填地址和配对码。

先看清三件事：

- 服务端**自己只说明文 HTTP**。局域网里用没问题；要出公网必须在前面挂反向代理做 HTTPS
  （`deploy/Caddyfile.example`），并把服务端打开 `COOKBOOK_REQUIRE_HTTPS=1`。
- 数据全在**一个目录**里：`cookbook-sync.db`（SQLite）、`blobs/`（图片）、`pair-code.txt`（配对码）。
  备份 = 拷这个目录（见下面「备份」）。
- 没有 Docker 也能跑：`sync-server-all.jar` 是自包含的，只要求 **Java 17 或更高**。

---

## 路线 A：Docker（推荐）

### 1. 开发机上打镜像

```bash
bash scripts/sync-server-image.sh          # 构建 cookbook-sync:1
SAVE=1 bash scripts/sync-server-image.sh   # 顺手导出 dist/cookbook-sync-image.tar
```

镜像里不编译（NAS 上装 Gradle + Android SDK 不值当），所以这一步必须在能跑本仓库构建的机器上做。
这台开发机没装 docker，脚本会直接告诉你，不会装一半。

### 2. 把镜像拷到 NAS

`dist/cookbook-sync-image.tar`（15MB 左右的胖包 + JRE 基础镜像）拷到 NAS 的共享目录，
例如 `/volume1/docker/cookbook/`。用 File Station 拖、`scp`、或者 `docker save` 之后走任意路径都行。

### 3. NAS 上载入并起服务

```bash
cd /volume1/docker/cookbook
docker load -i cookbook-sync-image.tar

# 把 deploy/docker-compose.nas.yml 拷到这个目录，然后：
docker compose -f docker-compose.nas.yml up -d
docker compose -f docker-compose.nas.yml logs | grep PAIR_CODE
```

`docker-compose.nas.yml` 和仓库里那个 `docker-compose.yml` 的区别：**它不构建**，直接用载入的镜像。

### 4. 拿配对码

```bash
cat data/pair-code.txt        # 一次性码，每 10 分钟换一个
docker compose -f docker-compose.nas.yml logs | grep PAIR_CODE
```

不想每次去看，就在 compose 里打开这一行（固定 6 位数字）：

```yaml
COOKBOOK_PAIR_CODE: "246810"
```

### 5. 手机配对

App 侧边栏 →「设置」→ 家庭共享：

1. 服务端地址填 `http://<NAS 的局域网 IP>:18088`（**别填 127.0.0.1**，那是手机自己）；
2. 配对码填上面那六位；
3. 点「配对」，然后「立即同步」看结果。

---

## 路线 B：不用 Docker（jar + systemd）

NAS 有 Java 17+ 时最省事（`java -version` 查一下，很多 NAS 自带的是 8 或 11，不够）。

1. 开发机上打包：`bash scripts/build.sh :sync-server:fatJar`
   → `sync-server/build/libs/sync-server-all.jar`
2. 拷到 NAS，比如 `/volume1/cookbook-sync/cookbook-sync-all.jar`；
3. 先手动跑一遍看看：`java -jar cookbook-sync-all.jar --port 18088 --data ./data`
4. 交给 systemd 看着：把 `deploy/cookbook-sync.service` 拷到 `/etc/systemd/system/`，
   按自己的用户名和路径改 `User` 与 `ExecStart`，然后

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now cookbook-sync
journalctl -u cookbook-sync -f
```

---

## 备份

服务端自带一个备份模式，**对着正在跑的实例执行也是安全的**（用 SQLite 的 `VACUUM INTO`
导一致性快照，做完就退出，不起服务）：

```bash
docker compose exec cookbook-sync \
  java -jar /app/cookbook-sync.jar --backup /data/backup-$(date +%F).db
```

图片不在库里，在 `data/blobs/`（按内容寻址、落盘是「临时文件 + 改名」，运行中拷也安全）：

```bash
tar czf /volume1/backups/cookbook-$(date +%F).tgz -C data blobs backup-$(date +%F).db
```

放进 NAS 的计划任务（每天凌晨 3 点，注意 crontab 里 `%` 要写成 `\%`）：

```cron
0 3 * * * cd /volume1/docker/cookbook && docker compose exec -T cookbook-sync java -jar /app/cookbook-sync.jar --backup /data/backup-$(date +\%F).db && tar czf /volume1/backups/cookbook-$(date +\%F).tgz -C data blobs backup-$(date +\%F).db
```

**恢复**：停容器 → 删掉 `data/cookbook-sync.db`、`cookbook-sync.db-wal`、`cookbook-sync.db-shm`
（WAL 和主库必须一起换，只换主库会读出旧的页）→ 把备份改名成 `cookbook-sync.db` → 起容器。

> 也可以停机后直接 `cp -r data`，但**运行中直接拷数据库文件会拿到不一致的快照**，
> 因为最近的写入还在 `-wal` 里。

---

## 升级

1. 开发机重新打镜像（tag 换一个更保险，比如 `cookbook-sync:2`，同时改 compose 里的 `image:`）；
2. 拷到 NAS → `docker load` → `docker compose -f docker-compose.nas.yml up -d`；
3. 升级前先做一次备份。库表是服务端启动时自建的，小版本升级不需要手工迁移。

---

## 出公网（可选，局域网够用就别做）

要在家外面也能同步，看方案文档的 §6.3：UGOS 自带 DDNS（IPv6 AAAA）+ 路由器放行端口 +
Caddy 容器做 TLS，HTTPS 端口改成 **8444** 绕开家宽对 80/443 的封锁，证书用 **DNS-01** 签。

配置模板在 `deploy/Caddyfile.example`。挂了反代之后服务端要多两个环境变量：

```yaml
COOKBOOK_TRUSTED_PROXIES: "127.0.0.1,::1"   # 反代的来源地址（Caddy 用 network_mode: host 时就是 127.0.0.1）
COOKBOOK_REQUIRE_HTTPS: "1"                   # 裸 HTTP 一律拒绝（/health 除外，留给监控）
```

服务端**只认可信来源给的 `X-Forwarded-For` / `X-Forwarded-Proto`**：来源不在名单里时，
伪造这两个头不但没用，还会被按 IP 限流当作真实来源处理。

---

## 排错

| 现象 | 先看这里 |
| --- | --- |
| 手机连不上 | 地址是不是填成了 `127.0.0.1`（要填 NAS 的局域网 IP）；NAS 防火墙放 18088 了吗；在 NAS 上 `curl http://127.0.0.1:18088/api/v1/health` 通不通 |
| 配对码不对 / 过期 | 一次性码 10 分钟换一次，`cat data/pair-code.txt` 拿最新的；或者干脆设固定码 |
| 401 / 「令牌失效」 | 服务端换过库或换过令牌，App 里点「断开」再重新配对一次 |
| 429 | 限流（默认每设备 240 次/分钟，配对 30 次/分钟），等一会儿再点 |
| 图片传不上去 | 单张超过 10MB 会被拒；`blobs/` 所在分区是不是满了；设置页的错误文字会说明是哪一步 |
| 容器起不来 | `docker compose logs`；宿主端口被占了吗（对外默认 18088，容器里始终 8080）；`data/` 目录的属主对不对 |
| 同步一直在转 | 看设置页最后一条错误；服务端 `journalctl`/`docker compose logs -f` 里有 4xx/5xx 的 code |

---

## 速查

| 东西 | 位置 |
| --- | --- |
| 配对码 | `<data>/pair-code.txt`，启动日志里的 `PAIR_CODE=` |
| 数据库 | `<data>/cookbook-sync.db`（+ `-wal` / `-shm`） |
| 图片 | `<data>/blobs/<哈希前两位>/<sha256>` |
| 体检 | `GET /api/v1/health`（免令牌） |
| 自检（开发机） | `bash scripts/sync-server-check.sh`（起临时实例 + curl 全流程 + 备份） |
