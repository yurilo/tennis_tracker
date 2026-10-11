/**
 * Tennis Tracker — Free Real-time Anonymous Telemetry & Live Dashboard Worker
 * Deployable on Cloudflare Workers (Free tier: 100k requests/day).
 *
 * Security & Efficiency Hardened:
 * - HTML entity escaping on all user-supplied metadata (Stored XSS mitigation)
 * - Optimized KV writes (1 write per unique DAU, capped ring buffer)
 * - Edge cached stats endpoint (Cache-Control: 60s) to preserve list quotas
 */

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, User-Agent",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    // 1. Ingestion Endpoint: POST /api/heartbeat
    if (request.method === "POST" && (url.pathname === "/api/heartbeat" || url.pathname === "/api/ping")) {
      try {
        const body = await request.json();
        if (!body || typeof body !== "object") {
          return new Response(JSON.stringify({ status: "error", message: "Invalid JSON payload" }), {
            status: 400,
            headers: { ...corsHeaders, "Content-Type": "application/json" }
          });
        }

        const now = new Date();
        const dateKey = now.toISOString().split("T")[0]; // YYYY-MM-DD
        
        // Strict input sanitization & length limits
        const rawId = String(body.anonymousId || "").trim();
        const anonymousId = /^[0-9a-fA-F-]{36}$/.test(rawId) ? rawId : "anon-" + Math.random().toString(36).substring(2, 9);
        const appVersion = String(body.appVersion || "1.0.17").substring(0, 16);
        const osVersion = String(body.osVersion || "Android").substring(0, 16);
        const deviceManufacturer = String(body.deviceManufacturer || "Generic").substring(0, 32);

        if (env.TELEMETRY_KV) {
          // Track unique DAU with 48h TTL (Only 1 write per unique user per day)
          const dauUserKey = `dau:${dateKey}:${anonymousId}`;
          const existing = await env.TELEMETRY_KV.get(dauUserKey);
          if (!existing) {
            await env.TELEMETRY_KV.put(dauUserKey, "1", { expirationTtl: 86400 * 2 });
            
            // Increment daily active counter key
            const dayCountKey = `count:${dateKey}`;
            const currentDayCount = parseInt(await env.TELEMETRY_KV.get(dayCountKey) || "0", 10);
            await env.TELEMETRY_KV.put(dayCountKey, (currentDayCount + 1).toString(), { expirationTtl: 86400 * 30 });
          }

          // Total pings counter
          const totalKey = "stats:total_pings";
          const currentTotal = parseInt(await env.TELEMETRY_KV.get(totalKey) || "0", 10);
          await env.TELEMETRY_KV.put(totalKey, (currentTotal + 1).toString());

          // Append to recent pings ring buffer (capped at 15 items, escaped)
          const recentKey = "stats:recent_pings";
          const recentList = JSON.parse(await env.TELEMETRY_KV.get(recentKey) || "[]");
          recentList.unshift({
            anonymousId: anonymousId.substring(0, 8) + "...",
            appVersion: escapeHtml(appVersion),
            osVersion: escapeHtml(osVersion),
            deviceManufacturer: escapeHtml(deviceManufacturer),
            timestamp: now.toISOString(),
            country: escapeHtml(request.headers.get("cf-ipcountry") || "US")
          });
          await env.TELEMETRY_KV.put(recentKey, JSON.stringify(recentList.slice(0, 15)));
        }

        return new Response(JSON.stringify({ status: "ok", received: true }), {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      } catch (err) {
        return new Response(JSON.stringify({ status: "error", message: "Malformed request" }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }
    }

    // 2. Stats API: GET /api/stats (Edge cached for 60s to prevent KV list quota exhaustion)
    if (request.method === "GET" && url.pathname === "/api/stats") {
      let stats = {
        totalPings: 0,
        todayActiveUsers: 0,
        recentEvents: []
      };

      if (env.TELEMETRY_KV) {
        stats.totalPings = parseInt(await env.TELEMETRY_KV.get("stats:total_pings") || "0", 10);
        const dateKey = new Date().toISOString().split("T")[0];
        
        // Fast read of today's counter without expensive KV prefix listing
        stats.todayActiveUsers = parseInt(await env.TELEMETRY_KV.get(`count:${dateKey}`) || "0", 10);
        stats.recentEvents = JSON.parse(await env.TELEMETRY_KV.get("stats:recent_pings") || "[]");
      }

      return new Response(JSON.stringify(stats, null, 2), {
        status: 200,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=60, s-maxage=60"
        }
      });
    }

    // 3. Live Dashboard UI: GET / (All user output HTML escaped)
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/dashboard")) {
      let totalPings = 0;
      let todayActive = 0;
      let recent = [];

      if (env.TELEMETRY_KV) {
        totalPings = parseInt(await env.TELEMETRY_KV.get("stats:total_pings") || "0", 10);
        const dateKey = new Date().toISOString().split("T")[0];
        todayActive = parseInt(await env.TELEMETRY_KV.get(`count:${dateKey}`) || "0", 10);
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
    <div class="badge"><div class="pulse"></div> Live Feeds Active</div>
  </div>

  <div class="grid">
    <div class="card">
      <div class="label">Today's Active Users (DAU)</div>
      <div class="value" id="dau">${todayActive}</div>
      <div class="hint">Unique devices active today</div>
    </div>
    <div class="card">
      <div class="label">Total App Launches</div>
      <div class="value" id="total">${totalPings}</div>
      <div class="hint">Cumulative launch heartbeats</div>
    </div>
    <div class="card">
      <div class="label">Latest Release</div>
      <div class="value" style="color: var(--accent);">v1.0.17</div>
      <div class="hint">Build #18 • Universal Tennis Tracker</div>
    </div>
  </div>

  <div class="table-container">
    <div class="table-header">
      <h2>Recent Live Heartbeats</h2>
      <span style="color: var(--muted); font-size: 0.8rem;">Auto-updates every 15s</span>
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
              <td class="mono">${escapeHtml(e.anonymousId)}</td>
              <td>${escapeHtml(e.appVersion)}</td>
              <td>Android ${escapeHtml(e.osVersion)}</td>
              <td>${escapeHtml(e.deviceManufacturer)}</td>
              <td>${escapeHtml(e.country)}</td>
              <td class="time">${escapeHtml(new Date(e.timestamp).toLocaleTimeString())}</td>
            </tr>
          `).join('')
        }
      </tbody>
    </table>
  </div>

  <script>
    function escapeHtml(str) {
      if (!str) return '';
      return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
    }

    // Live polling every 15s for real-time dashboard updates
    setInterval(async () => {
      try {
        const res = await fetch('/api/stats');
        if (res.ok) {
          const data = await res.json();
          document.getElementById('dau').innerText = data.todayActiveUsers || 0;
          document.getElementById('total').innerText = data.totalPings || 0;

          const tbody = document.getElementById('events-body');
          if (data.recentEvents && data.recentEvents.length > 0) {
            tbody.innerHTML = data.recentEvents.map(e => \`
              <tr>
                <td class="mono">\${escapeHtml(e.anonymousId)}</td>
                <td>\${escapeHtml(e.appVersion)}</td>
                <td>Android \${escapeHtml(e.osVersion)}</td>
                <td>\${escapeHtml(e.deviceManufacturer)}</td>
                <td>\${escapeHtml(e.country)}</td>
                <td class="time">\${escapeHtml(new Date(e.timestamp).toLocaleTimeString())}</td>
              </tr>
            \`).join('');
          }
        }
      } catch (err) {}
    }, 15000);
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
