/**
 * Tennis Tracker — Free Real-time Anonymous Telemetry & Live Dashboard Worker
 * Deployable on Cloudflare Workers (Free tier: 100k requests/day).
 *
 * Security & Efficiency Hardened:
 * - HTML entity escaping on all user-supplied metadata (Stored XSS mitigation)
 * - Optimized KV writes (1 write per unique DAU, capped ring buffer)
 * - Edge cached stats endpoint (Cache-Control: 60s) to preserve list quotas
 * - In-App Bug Reporting relay with KV rate limiting & GitHub API integration
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

// Category taxonomy mapping to canonical display names, enum constants, and GitHub label slugs
const CATEGORY_MAP = {
  "filter-tour": { slug: "filter-tour", name: "FILTER_TOUR_MISMATCH", displayName: "Filter / Tour Mismatch" },
  "filter_tour_mismatch": { slug: "filter-tour", name: "FILTER_TOUR_MISMATCH", displayName: "Filter / Tour Mismatch" },
  "filter / tour mismatch": { slug: "filter-tour", name: "FILTER_TOUR_MISMATCH", displayName: "Filter / Tour Mismatch" },

  "score-live-data": { slug: "score-live-data", name: "SCORE_LIVE_DATA", displayName: "Score / Live Data" },
  "score_live_data": { slug: "score-live-data", name: "SCORE_LIVE_DATA", displayName: "Score / Live Data" },
  "score / live data": { slug: "score-live-data", name: "SCORE_LIVE_DATA", displayName: "Score / Live Data" },

  "watch-highlights": { slug: "watch-highlights", name: "WATCH_HIGHLIGHTS", displayName: "Watch / Highlights" },
  "watch_highlights": { slug: "watch-highlights", name: "WATCH_HIGHLIGHTS", displayName: "Watch / Highlights" },
  "watch / highlights": { slug: "watch-highlights", name: "WATCH_HIGHLIGHTS", displayName: "Watch / Highlights" },

  "home-widget": { slug: "home-widget", name: "HOME_WIDGET", displayName: "Home Widget" },
  "home_widget": { slug: "home-widget", name: "HOME_WIDGET", displayName: "Home Widget" },
  "home widget": { slug: "home-widget", name: "HOME_WIDGET", displayName: "Home Widget" },

  "ui-layout": { slug: "ui-layout", name: "UI_LAYOUT", displayName: "UI / Layout" },
  "ui_layout": { slug: "ui-layout", name: "UI_LAYOUT", displayName: "UI / Layout" },
  "ui / layout": { slug: "ui-layout", name: "UI_LAYOUT", displayName: "UI / Layout" },

  "other": { slug: "other", name: "OTHER", displayName: "Other" }
};

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, User-Agent, X-Client-Version",
      "Access-Control-Max-Age": "86400"
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    // =========================================================================
    // 1. In-App Bug Reporting Relay: POST /bug-report or /api/bug-report
    // =========================================================================
    if (url.pathname === "/bug-report" || url.pathname === "/api/bug-report") {
      if (request.method !== "POST") {
        return new Response(JSON.stringify({
          error: "Method Not Allowed. Use POST for bug reports."
        }), {
          status: 405,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Check GitHub token configuration on relay
      const githubToken = env.GITHUB_TOKEN;
      if (!githubToken || typeof githubToken !== "string" || githubToken.trim() === "") {
        return new Response(JSON.stringify({
          fallback_required: true,
          reason: "RELAY_TOKEN_UNCONFIGURED",
          error: "GitHub relay token not configured on worker"
        }), {
          status: 503,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Parse JSON payload
      let body;
      try {
        body = await request.json();
      } catch (err) {
        return new Response(JSON.stringify({
          error: "Invalid JSON payload"
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      if (!body || typeof body !== "object") {
        return new Response(JSON.stringify({
          error: "Empty or invalid payload"
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Validate & sanitize summary
      const rawSummary = typeof body.summary === "string" ? body.summary.trim() : "";
      if (!rawSummary) {
        return new Response(JSON.stringify({
          error: "Missing required fields: summary cannot be empty"
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }
      const summary = rawSummary.slice(0, 500);

      // Validate & sanitize body / description
      const rawBody = typeof body.body === "string" ? body.body.trim() :
                      (typeof body.description === "string" ? body.description.trim() : "");
      if (!rawBody) {
        return new Response(JSON.stringify({
          error: "Missing required fields: description or body cannot be empty"
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // Validate & normalize category
      const rawCategory = typeof body.category === "string" ? body.category.trim() : "";
      if (!rawCategory) {
        return new Response(JSON.stringify({
          error: "Missing required fields: category cannot be empty"
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      const normalizedKey = rawCategory.toLowerCase();
      const categoryInfo = CATEGORY_MAP[normalizedKey];
      if (!categoryInfo) {
        return new Response(JSON.stringify({
          error: `Invalid category: ${rawCategory}`
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      // KV Rate Limiting: 5 requests per 10 minutes per IP/client identifier
      const clientIp = request.headers.get("cf-connecting-ip") ||
                       request.headers.get("x-real-ip") ||
                       request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
                       "127.0.0.1";

      const kv = env.TELEMETRY_KV || env.BUGREPORT_KV;
      if (kv) {
        const rateLimitKey = `ratelimit:bugreport:${clientIp}`;
        const now = Date.now();
        let rateRecord = null;
        const rawRecord = await kv.get(rateLimitKey);
        if (rawRecord) {
          try {
            rateRecord = JSON.parse(rawRecord);
          } catch (_) {
            rateRecord = { count: parseInt(rawRecord, 10) || 0, resetAt: now + 600000 };
          }
        }

        if (rateRecord && rateRecord.resetAt > now) {
          if (rateRecord.count >= 5) {
            const retryAfterSec = Math.max(1, Math.ceil((rateRecord.resetAt - now) / 1000));
            return new Response(JSON.stringify({
              error: "Rate limit exceeded. Maximum 5 bug reports per 10 minutes.",
              reason: "RATE_LIMIT_EXCEEDED",
              retry_after: retryAfterSec
            }), {
              status: 429,
              headers: {
                ...corsHeaders,
                "Content-Type": "application/json",
                "Retry-After": retryAfterSec.toString()
              }
            });
          }
          rateRecord.count += 1;
          const ttlSeconds = Math.max(60, Math.ceil((rateRecord.resetAt - now) / 1000));
          await kv.put(rateLimitKey, JSON.stringify(rateRecord), { expirationTtl: ttlSeconds });
        } else {
          rateRecord = { count: 1, resetAt: now + 600000 };
          await kv.put(rateLimitKey, JSON.stringify(rateRecord), { expirationTtl: 600 });
        }
      }

      // Construct issue body if diagnostic_snapshot is provided separately
      let fullIssueBody = rawBody.slice(0, 65536);
      if (body.diagnostic_snapshot && typeof body.diagnostic_snapshot === "object" && !fullIssueBody.includes("<!-- BUGREPORT_JSON_START -->")) {
        const jsonBlock = JSON.stringify(body.diagnostic_snapshot, null, 2);
        fullIssueBody += `\n\n<details>\n<summary>Diagnostic Bugreport Bundle (Machine-Readable JSON)</summary>\n\n<!-- BUGREPORT_JSON_START -->\n${jsonBlock}\n<!-- BUGREPORT_JSON_END -->\n</details>`;
      }

      // Format issue title & labels
      const issueTitle = summary.startsWith("[") ? summary : `[${categoryInfo.displayName}] ${summary}`;
      const labels = ["bug", "in-app-report", categoryInfo.slug];

      const githubRepo = env.GITHUB_REPO || "yurilo/tennis_tracker";
      const githubUrl = `https://api.github.com/repos/${githubRepo}/issues`;

      try {
        const githubRes = await fetch(githubUrl, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${githubToken.trim()}`,
            "Accept": "application/vnd.github+json",
            "User-Agent": "TennisTracker-BugReport-Relay/1.0",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28"
          },
          body: JSON.stringify({
            title: issueTitle,
            body: fullIssueBody,
            labels: labels
          })
        });

        const githubData = await githubRes.json().catch(() => ({}));

        if (!githubRes.ok) {
          return new Response(JSON.stringify({
            success: false,
            fallback_required: true,
            reason: "GITHUB_API_ERROR",
            error: githubData.message || `GitHub returned HTTP ${githubRes.status}`,
            status_code: githubRes.status
          }), {
            status: 502,
            headers: { ...corsHeaders, "Content-Type": "application/json" }
          });
        }

        return new Response(JSON.stringify({
          success: true,
          issue_number: githubData.number,
          issue_url: githubData.html_url
        }), {
          status: 201,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      } catch (fetchErr) {
        return new Response(JSON.stringify({
          success: false,
          fallback_required: true,
          reason: "RELAY_NETWORK_ERROR",
          error: `Failed to contact GitHub: ${fetchErr.message}`
        }), {
          status: 502,
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }
    }

    // =========================================================================
    // 2. Telemetry Ingestion Endpoint: POST /api/heartbeat or /api/ping
    // =========================================================================
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

    // =========================================================================
    // 3. Stats API: GET /api/stats (Edge cached for 60s)
    // =========================================================================
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

    // =========================================================================
    // 4. Live Dashboard UI: GET / or /dashboard
    // =========================================================================
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
