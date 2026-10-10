/**
 * Tennis Tracker — Free Real-time Anonymous Telemetry & Live Dashboard Worker
 * Deployable on Cloudflare Workers (Free tier: 100k requests/day).
 *
 * Setup:
 * 1. Create a free Cloudflare account at https://workers.cloudflare.com
 * 2. Create a Worker, paste this code.
 * 3. Bind a KV namespace named `TELEMETRY_KV` in Worker Settings -> Variables -> KV Namespace Bindings.
 * 4. Visit your Worker URL (e.g. https://tennis-telemetry.yoursubdomain.workers.dev) to see your live dashboard!
 */

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Enable CORS for testing
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, User-Agent",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    // 1. Telemetry Ingestion Endpoint: POST /api/heartbeat
    if (request.method === "POST" && (url.pathname === "/api/heartbeat" || url.pathname === "/api/ping")) {
      try {
        const body = await request.json();
        const now = new Date();
        const dateKey = now.toISOString().split("T")[0]; // YYYY-MM-DD
        const anonymousId = body.anonymousId || "anon-" + Math.random().toString(36).substring(2, 9);
        const appVersion = body.appVersion || "1.0.16";
        const osVersion = body.osVersion || "Android";
        const deviceManufacturer = body.deviceManufacturer || "Unknown";

        // Store in KV if available
        if (env.TELEMETRY_KV) {
          // Increment total pings
          const totalKey = "stats:total_pings";
          const currentTotal = parseInt(await env.TELEMETRY_KV.get(totalKey) || "0", 10);
          await env.TELEMETRY_KV.put(totalKey, (currentTotal + 1).toString());

          // Mark unique user for today (DAU)
          const dauUserKey = `dau:${dateKey}:${anonymousId}`;
          await env.TELEMETRY_KV.put(dauUserKey, "1", { expirationTtl: 86400 * 2 }); // 48h TTL

          // Mark unique user for 30 days (MAU)
          const mauUserKey = `mau:${anonymousId}`;
          await env.TELEMETRY_KV.put(mauUserKey, dateKey, { expirationTtl: 86400 * 35 });

          // Track version count
          const verKey = `ver:${appVersion}`;
          const currentVer = parseInt(await env.TELEMETRY_KV.get(verKey) || "0", 10);
          await env.TELEMETRY_KV.put(verKey, (currentVer + 1).toString());

          // Track OS count
          const osKey = `os:${osVersion}`;
          const currentOs = parseInt(await env.TELEMETRY_KV.get(osKey) || "0", 10);
          await env.TELEMETRY_KV.put(osKey, (currentOs + 1).toString());

          // Store in recent activity buffer (FIFO ring of last 25 events)
          const recentKey = "stats:recent_pings";
          const recentList = JSON.parse(await env.TELEMETRY_KV.get(recentKey) || "[]");
          recentList.unshift({
            anonymousId: anonymousId.substring(0, 8) + "...",
            appVersion,
            osVersion,
            deviceManufacturer,
            timestamp: now.toISOString(),
            country: request.headers.get("cf-ipcountry") || "Unknown"
          });
          await env.TELEMETRY_KV.put(recentKey, JSON.stringify(recentList.slice(0, 30)));
        }

        return new Response(JSON.stringify({ status: "ok", received: true }), {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      } catch (err) {
        return new Response(JSON.stringify({ status: "error", message: err.message }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }
    }

    // 2. Stats API: GET /api/stats
    if (request.method === "GET" && url.pathname === "/api/stats") {
      let stats = {
        totalPings: 0,
        todayActiveUsers: 0,
        monthlyActiveUsers: 0,
        recentEvents: []
      };

      if (env.TELEMETRY_KV) {
        stats.totalPings = parseInt(await env.TELEMETRY_KV.get("stats:total_pings") || "0", 10);
        const now = new Date();
        const dateKey = now.toISOString().split("T")[0];

        // List keys for DAU
        const dauList = await env.TELEMETRY_KV.list({ prefix: `dau:${dateKey}:` });
        stats.todayActiveUsers = dauList.keys.length;

        // List keys for MAU
        const mauList = await env.TELEMETRY_KV.list({ prefix: "mau:" });
        stats.monthlyActiveUsers = mauList.keys.length;

        stats.recentEvents = JSON.parse(await env.TELEMETRY_KV.get("stats:recent_pings") || "[]");
      }

      return new Response(JSON.stringify(stats, null, 2), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" }
      });
    }

    // 3. Live Dashboard UI: GET /
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/dashboard")) {
      let totalPings = 0;
      let todayActive = 0;
      let monthlyActive = 0;
      let recent = [];

      if (env.TELEMETRY_KV) {
        totalPings = parseInt(await env.TELEMETRY_KV.get("stats:total_pings") || "0", 10);
        const dateKey = new Date().toISOString().split("T")[0];
        const dauList = await env.TELEMETRY_KV.list({ prefix: `dau:${dateKey}:` });
        todayActive = dauList.keys.length;
        const mauList = await env.TELEMETRY_KV.list({ prefix: "mau:" });
        monthlyActive = mauList.keys.length;
        recent = JSON.parse(await env.TELEMETRY_KV.get("stats:recent_pings") || "[]");
      }

      const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Tennis Tracker — Live Active Users Dashboard</title>
  <style>
    :root {
      --bg: #0b132b;
      --card-bg: #1c2541;
      --accent: #ffb800;
      --green: #10b981;
      --text: #f8fafc;
      --muted: #94a3b8;
      --border: #334155;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background-color: var(--bg); color: var(--text); padding: 2rem; min-height: 100vh; }
    .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2rem; border-bottom: 1px solid var(--border); padding-bottom: 1.5rem; }
    .brand { display: flex; align-items: center; gap: 0.75rem; }
    .brand h1 { font-size: 1.75rem; font-weight: 700; }
    .badge { background: rgba(16, 185, 129, 0.2); color: var(--green); padding: 0.25rem 0.75rem; border-radius: 999px; font-size: 0.85rem; font-weight: 600; display: inline-flex; align-items: center; gap: 0.35rem; }
    .pulse { width: 8px; height: 8px; background: var(--green); border-radius: 50%; animation: pulse 2s infinite; }
    @keyframes pulse { 0% { opacity: 1; transform: scale(1); } 50% { opacity: 0.4; transform: scale(1.3); } 100% { opacity: 1; transform: scale(1); } }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1.25rem; margin-bottom: 2rem; }
    .card { background: var(--card-bg); padding: 1.5rem; border-radius: 12px; border: 1px solid var(--border); box-shadow: 0 4px 6px -1px rgba(0,0,0,0.2); }
    .card .label { font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--muted); margin-bottom: 0.5rem; }
    .card .value { font-size: 2.25rem; font-weight: 800; color: #ffffff; }
    .card .hint { font-size: 0.75rem; color: var(--muted); margin-top: 0.5rem; }
    .table-container { background: var(--card-bg); border-radius: 12px; border: 1px solid var(--border); overflow: hidden; }
    .table-header { padding: 1rem 1.5rem; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; }
    .table-header h2 { font-size: 1.15rem; font-weight: 600; }
    table { width: 100%; border-collapse: collapse; text-align: left; font-size: 0.9rem; }
    th { padding: 0.75rem 1.5rem; background: rgba(255, 255, 255, 0.02); color: var(--muted); font-weight: 600; border-bottom: 1px solid var(--border); }
    td { padding: 0.85rem 1.5rem; border-bottom: 1px solid rgba(255, 255, 255, 0.05); }
    tr:last-child td { border-bottom: none; }
    .mono { font-family: monospace; color: var(--accent); }
    .time { color: var(--muted); font-size: 0.8rem; }
  </style>
</head>
<body>
  <div class="header">
    <div class="brand">
      <span style="font-size: 2rem;">🎾</span>
      <div>
        <h1>Tennis Tracker</h1>
        <p style="color: var(--muted); font-size: 0.85rem;">Live Real-Time Active Users & Telemetry</p>
      </div>
    </div>
    <div class="badge"><div class="pulse"></div> Live Live Feeds Active</div>
  </div>

  <div class="grid">
    <div class="card">
      <div class="label">Today's Active Users (DAU)</div>
      <div class="value" id="dau">${todayActive}</div>
      <div class="hint">Unique devices active today</div>
    </div>
    <div class="card">
      <div class="label">Monthly Active Users (MAU)</div>
      <div class="value" id="mau">${monthlyActive}</div>
      <div class="hint">Unique devices past 30 days</div>
    </div>
    <div class="card">
      <div class="label">Total App Launches</div>
      <div class="value" id="total">${totalPings}</div>
      <div class="hint">Cumulative launch heartbeats</div>
    </div>
    <div class="card">
      <div class="label">Latest Release</div>
      <div class="value" style="color: var(--accent);">v1.0.16</div>
      <div class="hint">Build #17 • Google Play / GitHub</div>
    </div>
  </div>

  <div class="table-container">
    <div class="table-header">
      <h2>Recent Live Heartbeats</h2>
      <span style="color: var(--muted); font-size: 0.8rem;">Auto-updates every 10s</span>
    </div>
    <table>
      <thead>
        <tr>
          <th>Anonymous Device ID</th>
          <th>Version</th>
          <th>OS Release</th>
          <th>Device</th>
          <th>Country</th>
          <th>Timestamp</th>
        </tr>
      </thead>
      <tbody id="events-body">
        ${recent.length === 0 ? '<tr><td colspan="6" style="text-align:center; color:var(--muted); padding:2rem;">No heartbeat events recorded yet. Launch the app to see live events.</td></tr>' : 
          recent.map(e => `
            <tr>
              <td class="mono">\${e.anonymousId}</td>
              <td>\${e.appVersion}</td>
              <td>Android \${e.osVersion}</td>
              <td>\${e.deviceManufacturer}</td>
              <td>\${e.country}</td>
              <td class="time">\${new Date(e.timestamp).toLocaleTimeString()}</td>
            </tr>
          `).join('')
        }
      </tbody>
    </table>
  </div>

  <script>
    // Live polling every 10s for real-time dashboard updates
    setInterval(async () => {
      try {
        const res = await fetch('/api/stats');
        if (res.ok) {
          const data = await res.json();
          document.getElementById('dau').innerText = data.todayActiveUsers;
          document.getElementById('mau').innerText = data.monthlyActiveUsers;
          document.getElementById('total').innerText = data.totalPings;

          const tbody = document.getElementById('events-body');
          if (data.recentEvents && data.recentEvents.length > 0) {
            tbody.innerHTML = data.recentEvents.map(e => \`
              <tr>
                <td class="mono">\${e.anonymousId}</td>
                <td>\${e.appVersion}</td>
                <td>Android \${e.osVersion}</td>
                <td>\${e.deviceManufacturer}</td>
                <td>\${e.country}</td>
                <td class="time">\${new Date(e.timestamp).toLocaleTimeString()}</td>
              </tr>
            \`).join('');
          }
        }
      } catch (err) {}
    }, 10000);
  </script>
</body>
</html>`;

      return new Response(html, {
        headers: { "Content-Type": "text/html; charset=utf-8" }
      });
    }

    return new Response("Not Found", { status: 404 });
  }
};
