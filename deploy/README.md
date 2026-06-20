# Deploying Clele under `https://qd.ax/clele`

Clele builds as a single Spring Boot jar that serves both the React SPA and the `/api`. In
production it runs behind an Apache reverse proxy under the `/clele` subpath, as a systemd service
owned by a dedicated `clele` system user, talking to a local PostgreSQL.

The subpath is wired with Spring's `server.servlet.context-path=/clele` (prod profile) and the
frontend's Vite `base=/clele/` (set at build time via `VITE_BASE`). The browser, Apache, and Spring
all use the `/clele` prefix, so the session cookie auto-scopes to `/clele` and no path rewriting is
needed in Apache.

## One-time server setup

1. **Java 21** at `/opt/java/21/bin/java` (or edit `ExecStart` in `clele.service` and `JAVA_BIN`
   in `deploy.sh` to match your path).

2. **PostgreSQL** running locally with the database and user already created:
   ```sql
   CREATE DATABASE partsdb;
   CREATE USER partsuser WITH PASSWORD '<strong-password>';
   GRANT ALL PRIVILEGES ON DATABASE partsdb TO partsuser;
   ```
   (Flyway creates the schema on first start.)

3. **Apache modules + vhost.** Enable the proxy/header modules and add the directives from
   `clele-apache.conf` inside your existing `qd.ax` `:443` TLS `<VirtualHost>`:
   ```sh
   sudo a2enmod proxy proxy_http headers
   sudo systemctl reload apache2
   ```

4. **Environment file.** `deploy.sh` installs `/etc/clele/clele.env` from `clele.env.example` on the
   first deploy (mode 640, owned `root:clele`). Edit it and set a real `DB_PASSWORD` and
   `ANTHROPIC_API_KEY`. It is never overwritten on later deploys.

## Deploy

From the repo root, with the SSH target supplied via env (or edit the top of `deploy.sh`):

```sh
DEPLOY_HOST=qd.ax DEPLOY_USER=<your-ssh-user> ./deploy/deploy.sh
```

The script:
1. builds the jar locally with `VITE_BASE=/clele/ mvn21 clean package`,
2. creates the `clele` system user + `/opt/clele` and `/etc/clele` (idempotent),
3. uploads the jar, the systemd unit, and the env template,
4. runs `systemctl enable --now clele` / `restart`, then prints status + a local health check.

The SSH user needs passwordless (or interactive) `sudo` on the server.

## After deploying

- Browse `https://qd.ax/clele/` and **change the bootstrap admin password** immediately
  (`admin@clele.local` / `admin`, seeded by Flyway V10) via the Users screen.
- Logs: `journalctl -u clele -f`.

## Security notes (what the prod profile hardens)

- Secrets come from `/etc/clele/clele.env` (the jar carries no API key or DB password).
- Session cookie is `Secure` + `HttpOnly` + `SameSite=Lax`; `forward-headers-strategy=framework`
  makes Spring trust Apache's `X-Forwarded-Proto`.
- Swagger UI / OpenAPI docs are disabled; error responses omit stack traces and messages.
- The image-proxy / image-from-url endpoints reject URLs that resolve to private, loopback,
  link-local, or cloud-metadata addresses (SSRF guard).
