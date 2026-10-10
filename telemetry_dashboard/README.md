# 🎾 Tennis Tracker — Free Real-Time Telemetry Dashboard

A lightweight, zero-maintenance, 100% free Cloudflare Worker backend and live analytics dashboard for tracking active users of the **Tennis Tracker** Android app.

---

## 🌟 What It Displays

- 🟢 **Daily Active Users (DAU)**: Unique devices that launched the app today.
- 📱 **Monthly Active Users (MAU)**: Unique devices active over the past 30 days.
- 🚀 **Total Launches**: Cumulative app launch count.
- 📊 **Live Stream**: Real-time incoming heartbeats with app version, Android OS version, device manufacturer, and approximate country.
- ⏱️ **Auto-Refresh**: Live dashboard polls every 10 seconds.

---

## 🚀 60-Second Setup (100% Free on Cloudflare Workers)

1. Sign up for a free account at [workers.cloudflare.com](https://workers.cloudflare.com) (free plan includes **100,000 requests/day**).
2. Click **Create Application** ➔ **Create Worker**.
3. Name it `tennis-telemetry` and click **Deploy**.
4. Click **Edit code**, delete the default code, and paste the entire contents of [`worker.js`](worker.js).
5. Click **Deploy**.
6. **Add KV Storage**:
   - In Cloudflare Dashboard, go to **Workers & Pages** ➔ **KV**.
   - Click **Create a namespace**, name it `TELEMETRY_KV`.
   - Go back to your `tennis-telemetry` worker ➔ **Settings** ➔ **Variables** ➔ **KV Namespace Bindings**.
   - Click **Add binding**:
     - Variable name: `TELEMETRY_KV`
     - KV namespace: select `TELEMETRY_KV`
   - Click **Save and Deploy**.
7. Visit your worker URL in your browser (e.g. `https://tennis-telemetry.<your-subdomain>.workers.dev`) to see your live dashboard!
8. Point the app to your worker URL by setting `DEFAULT_ENDPOINT` in `AnonymousTelemetryService.kt` to `https://tennis-telemetry.<your-subdomain>.workers.dev/api/heartbeat`.
