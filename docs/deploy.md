# Deploying to Render and Neon (free tiers)

RoomKeeper runs live on free tiers: the app on a [Render](https://render.com) free web service built from the Dockerfile, Redis on a Render free Key Value instance, and Postgres on [Neon](https://neon.com)'s free plan. Render's own free Postgres is deleted 30 days after it's created, so it isn't used. [`render.yaml`](../render.yaml) is the Blueprint that describes the Render side.

## What the free tiers mean

- **The app sleeps.** A free web service spins down after 15 minutes without requests and takes about a minute to wake, plus about two more for the JVM to start on 0.1 CPU. The first request after a quiet spell is slow; the rest aren't. While it sleeps, the scheduled jobs (hold expiry, inventory roll, outbox safety net) don't run; they catch up when it wakes.
- **512 MB of memory.** `JAVA_TOOL_OPTIONS` in the Blueprint sizes the JVM for it (about 330 MB used at rest, 360 MB after the smoke test, in a rehearsal at the same limits), and Tomcat keeps at most 40 request threads.
- **Redis isn't persisted.** A restart empties it: signed-in users log in again, rate limits start over, and cached searches are rebuilt. Outbox events aren't lost: Postgres resends whatever Redis dropped (see [outbox streams](outbox-streams.md)).
- **Neon scales to zero** after 5 minutes without queries; the next query wakes it in about a second. The free plan gives 1 GB of storage and 100 compute-hours a month, plenty for an app that sleeps.
- **No payments yet.** Without Stripe keys, paying answers 503 and everything else works. See "Adding Stripe" below.

## First deploy

1. **Neon.** Sign up (GitHub works), create a project named `roomkeeper` in **AWS Asia Pacific (Singapore)**, the same region as the Render services, with the newest Postgres version offered. Under **Connect**, turn connection pooling **off**: the direct endpoint, whose host has no `-pooler`, because Flyway's migrations shouldn't run through PgBouncer's transaction pooling. Note the host, database, role and password.
2. **Render.** Sign up with GitHub, then **New → Blueprint**, pick `gauravgondane26/roomkeeper` and the `main` branch. Render reads `render.yaml` and asks for the values it doesn't hold:
   - `DATABASE_URL`: `jdbc:postgresql://HOST/DATABASE?sslmode=require` with Neon's host and database
   - `DATABASE_USERNAME` and `DATABASE_PASSWORD`: Neon's role and password
   - `SPRING_DATA_REDIS_URL`: leave it empty for now, or any placeholder if Render insists on a value (step 3 fills it in)

   Apply it. Render creates the `roomkeeper-redis` Key Value instance and the `roomkeeper` web service. The web service's first deploy fails at startup with "No Redis is configured": prod won't run without a Redis that asks for a password.
3. **Redis password.** Open `roomkeeper-redis` → **Info** → **Connections** → **Enable Internal Authentication**. Copy the **Internal Key Value URL**, which now carries the password (`redis://default:…@red-…:6379`).
4. **Connect the app.** Open the `roomkeeper` web service → **Environment**, set `SPRING_DATA_REDIS_URL` to that URL, and save with a new deploy. The build takes several minutes and the start about two more; the deploy goes live once `/readyz` answers.
5. **Open it.** The service's URL (`https://roomkeeper….onrender.com`) shows Swagger UI. The admin account is `admin@roomkeeper.local`; its password is `ADMIN_PASSWORD` under **Environment** (Render generated it, like `JWT_SECRET`).

After that, every push to `main` deploys once its CI checks pass (`autoDeployTrigger: checksPass`).

## Checking the client IP

The rate limits count per client IP, which the app takes from `X-Forwarded-For` only when the request comes from a proxy matching `TRUSTED_PROXIES`. The Blueprint trusts private addresses, where Render's proxy should connect from. To confirm, log in once with a wrong password and look in the service's **Logs** for `Failed login for … from …`. It should show your own public IP. A private address (`10.…`) there would mean Render's proxy isn't matched, and every client would share one bucket: widen `TRUSTED_PROXIES` to match it.

## Adding Stripe (test mode)

1. In the Stripe dashboard (test mode), add a webhook endpoint at `https://YOUR-SERVICE.onrender.com/webhook/payment` for `checkout.session.completed`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed` and `checkout.session.expired`, and copy its signing secret.
2. Set on the web service: `STRIPE_SECRET_KEY` (an `sk_test_` key), `STRIPE_WEBHOOK_SECRET` (that signing secret), `ROOMKEEPER_PAYMENTS_SUCCESSURL` (`https://YOUR-SERVICE.onrender.com/payments/complete.html?session_id={CHECKOUT_SESSION_ID}`) and `ROOMKEEPER_PAYMENTS_CANCELURL` (`https://YOUR-SERVICE.onrender.com/payments/cancelled.html`).

## Settings the Blueprint makes

| Setting | Why |
|---|---|
| `PORT=8080` | Render routes traffic to `PORT`; Actuator stays on 8081, which isn't exposed |
| `healthCheckPath: /readyz` | A new deploy takes traffic only once it reaches Postgres |
| `JWT_SECRET`, `ADMIN_PASSWORD` | Generated by Render (random 256-bit, base64), so they're never in the repository |
| `API_DOCS_ENABLED=true` | Swagger UI at `/` is the live API's client |
| `TRUSTED_PROXIES` | Private addresses, so the rate limits see each client (see above) |
| `JAVA_TOOL_OPTIONS`, `SERVER_TOMCAT_THREADS_MAX` | Fit the JVM into 512 MB and 0.1 CPU |
| Key Value `ipAllowList: []` | No connections from outside Render |
| Key Value `maxmemoryPolicy: volatile-ttl` | As in Compose: evict what expires soonest, keep refresh tokens and idempotency keys |
