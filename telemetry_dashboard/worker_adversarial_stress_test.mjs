// telemetry_dashboard/worker_adversarial_stress_test.mjs
// Adversarial Stress & Edge Case Test Suite for worker.js (Milestone 2)

import worker from "./worker.js";
import assert from "node:assert";

class MockKV {
  constructor() {
    this.store = new Map();
  }
  async get(key) {
    return this.store.get(key) || null;
  }
  async put(key, value, options) {
    this.store.set(key, String(value));
  }
  clear() {
    this.store.clear();
  }
}

async function runAdversarialSuite() {
  console.log("================================================================");
  console.log("       ADVERSARIAL STRESS TEST SUITE: worker.js                 ");
  console.log("================================================================");

  let passed = 0;
  let failed = 0;

  async function test(name, fn) {
    try {
      await fn();
      console.log(`  [PASS] ${name}`);
      passed++;
    } catch (err) {
      console.error(`  [FAIL] ${name}: ${err.message}`);
      if (err.stack) {
        console.error("         " + err.stack.split("\n").slice(1, 3).join("\n         "));
      }
      failed++;
    }
  }

  const validTokenEnv = { GITHUB_TOKEN: "ghp_adversarial_stress_test_token" };

  // =========================================================================
  // SECTION 1: Rapid Bursts & Rate Limit Boundary (5 allowed, 6th 429)
  // =========================================================================
  console.log("\n--- Section 1: Rapid Bursts & Rate Limiting ---");

  await test("Rapid burst: Exactly 5 requests allowed, 6th rejected with HTTP 429", async () => {
    const kv = new MockKV();
    const env = { ...validTokenEnv, TELEMETRY_KV: kv };
    const originalFetch = globalThis.fetch;
    let ghCallCount = 0;
    globalThis.fetch = async () => {
      ghCallCount++;
      return new Response(JSON.stringify({ number: 100 + ghCallCount, html_url: `https://github.com/issue/${ghCallCount}` }), {
        status: 201,
        headers: { "Content-Type": "application/json" }
      });
    };

    try {
      const clientIp = "198.51.100.42";
      for (let i = 1; i <= 5; i++) {
        const req = new Request("https://worker.dev/bug-report", {
          method: "POST",
          headers: { "Content-Type": "application/json", "cf-connecting-ip": clientIp },
          body: JSON.stringify({ category: "home-widget", summary: `Burst request ${i}`, body: "Details" })
        });
        const res = await worker.fetch(req, env, {});
        assert.strictEqual(res.status, 201, `Request ${i} should be 201, got ${res.status}`);
        const data = await res.json();
        assert.strictEqual(data.success, true);
        assert.strictEqual(data.issue_number, 100 + i);
      }
      assert.strictEqual(ghCallCount, 5, "Downstream GitHub API called exactly 5 times");

      // 6th request: Rate limited!
      const req6 = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json", "cf-connecting-ip": clientIp },
        body: JSON.stringify({ category: "home-widget", summary: "Burst request 6", body: "Details" })
      });
      const res6 = await worker.fetch(req6, env, {});
      assert.strictEqual(res6.status, 429, `6th request must return 429, got ${res6.status}`);
      const data6 = await res6.json();
      assert.strictEqual(data6.reason, "RATE_LIMIT_EXCEEDED");
      assert.ok(data6.retry_after > 0, "retry_after should be positive integer");
      assert.strictEqual(res6.headers.get("Retry-After"), data6.retry_after.toString());
      assert.strictEqual(res6.headers.get("Access-Control-Allow-Origin"), "*");

      // Subsequent 7th, 8th, 9th requests still blocked
      for (let j = 7; j <= 9; j++) {
        const reqExtra = new Request("https://worker.dev/bug-report", {
          method: "POST",
          headers: { "Content-Type": "application/json", "cf-connecting-ip": clientIp },
          body: JSON.stringify({ category: "home-widget", summary: `Burst request ${j}`, body: "Details" })
        });
        const resExtra = await worker.fetch(reqExtra, env, {});
        assert.strictEqual(resExtra.status, 429);
      }
      assert.strictEqual(ghCallCount, 5, "Downstream GitHub API was NOT called for blocked requests");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  await test("Rate limit isolation: Second IP can still make requests when first IP is blocked", async () => {
    const kv = new MockKV();
    const env = { ...validTokenEnv, TELEMETRY_KV: kv };
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ number: 999, html_url: "https://github.com/issue/999" }), {
      status: 201, headers: { "Content-Type": "application/json" }
    });

    try {
      const blockedIp = "192.0.2.1";
      const allowedIp = "192.0.2.2";

      // Exhaust IP 1
      for (let i = 0; i < 5; i++) {
        await worker.fetch(new Request("https://worker.dev/bug-report", {
          method: "POST",
          headers: { "Content-Type": "application/json", "cf-connecting-ip": blockedIp },
          body: JSON.stringify({ category: "other", summary: "Exhaust", body: "Body" })
        }), env, {});
      }

      // Check IP 1 is blocked
      const res1 = await worker.fetch(new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json", "cf-connecting-ip": blockedIp },
        body: JSON.stringify({ category: "other", summary: "Blocked", body: "Body" })
      }), env, {});
      assert.strictEqual(res1.status, 429);

      // Check IP 2 succeeds
      const res2 = await worker.fetch(new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json", "cf-connecting-ip": allowedIp },
        body: JSON.stringify({ category: "other", summary: "Allowed", body: "Body" })
      }), env, {});
      assert.strictEqual(res2.status, 201);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  await test("IP Header Precedence & x-forwarded-for parsing", async () => {
    const kv = new MockKV();
    const env = { ...validTokenEnv, TELEMETRY_KV: kv };
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ number: 1, html_url: "url" }), { status: 201 });

    try {
      // Test x-forwarded-for with multiple proxies: client ip is first comma-separated token
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-forwarded-for": "203.0.113.195, 70.41.3.18, 150.172.238.178"
        },
        body: JSON.stringify({ category: "other", summary: "XFF test", body: "Body" })
      });
      await worker.fetch(req, env, {});
      const stored = await kv.get("ratelimit:bugreport:203.0.113.195");
      assert.ok(stored, "Key must be saved under the first IP of x-forwarded-for");

      // Test fallback to 127.0.0.1 when no IP headers provided
      const reqNoIp = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: "other", summary: "No IP header", body: "Body" })
      });
      await worker.fetch(reqNoIp, env, {});
      const storedLocal = await kv.get("ratelimit:bugreport:127.0.0.1");
      assert.ok(storedLocal, "Key must be saved under fallback 127.0.0.1");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  await test("Corrupted KV Rate Limit Record does not crash worker", async () => {
    const kv = new MockKV();
    const env = { ...validTokenEnv, TELEMETRY_KV: kv };
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ number: 2, html_url: "url" }), { status: 201 });

    try {
      // Put non-JSON junk into KV
      await kv.put("ratelimit:bugreport:1.1.1.1", "corrupted-json-data{{");

      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json", "cf-connecting-ip": "1.1.1.1" },
        body: JSON.stringify({ category: "other", summary: "Survives corrupt KV", body: "Body" })
      });

      const res = await worker.fetch(req, env, {});
      assert.strictEqual(res.status, 201, "Worker should catch parse error and proceed");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  // =========================================================================
  // SECTION 2: Missing or Invalid env.GITHUB_TOKEN (HTTP 503)
  // =========================================================================
  console.log("\n--- Section 2: Missing / Invalid GitHub Token ---");

  const invalidTokenValues = [
    { label: "undefined", val: undefined },
    { label: "null", val: null },
    { label: "empty string", val: "" },
    { label: "whitespace only", val: "    " },
    { label: "number", val: 12345 },
    { label: "boolean", val: false }
  ];

  for (const { label, val } of invalidTokenValues) {
    await test(`Missing or invalid GITHUB_TOKEN (${label}) returns 503 with fallback_required: true`, async () => {
      const env = { GITHUB_TOKEN: val };
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: "filter-tour", summary: "Summary", body: "Body" })
      });
      const res = await worker.fetch(req, env, {});
      assert.strictEqual(res.status, 503);
      assert.strictEqual(res.headers.get("Access-Control-Allow-Origin"), "*");
      const json = await res.json();
      assert.strictEqual(json.fallback_required, true);
      assert.strictEqual(json.reason, "RELAY_TOKEN_UNCONFIGURED");
      assert.ok(json.error.includes("token not configured"));
    });
  }

  // =========================================================================
  // SECTION 3: Malformed Request Bodies & Boundary Fields
  // =========================================================================
  console.log("\n--- Section 3: Malformed Bodies & Missing Fields ---");

  const malformedBodies = [
    { label: "Raw unquoted string", body: "This is not json" },
    { label: "Broken JSON braces", body: "{\"summary\": \"open" },
    { label: "Trailing comma JSON", body: "{\"summary\": \"foo\",}" },
    { label: "Binary null bytes", body: "\0\0\0" },
    { label: "Empty string", body: "" }
  ];

  for (const { label, body } of malformedBodies) {
    await test(`Malformed body (${label}) returns 400`, async () => {
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 400);
      const json = await res.json();
      assert.ok(json.error.includes("Invalid JSON"));
      assert.strictEqual(res.headers.get("Access-Control-Allow-Origin"), "*");
    });
  }

  const invalidJsonStructures = [
    { label: "JSON array", body: JSON.stringify([1, 2, 3]) },
    { label: "JSON string primitive", body: JSON.stringify("hello") },
    { label: "JSON number primitive", body: JSON.stringify(12345) },
    { label: "JSON boolean primitive", body: JSON.stringify(true) }
  ];

  for (const { label, body } of invalidJsonStructures) {
    await test(`Non-object JSON structure (${label}) returns 400`, async () => {
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 400);
    });
  }

  const missingFieldCases = [
    { label: "Missing summary property", payload: { category: "other", body: "B" } },
    { label: "Summary is empty string", payload: { category: "other", summary: "", body: "B" } },
    { label: "Summary is whitespace only", payload: { category: "other", summary: "    \t \n", body: "B" } },
    { label: "Summary is non-string (number)", payload: { category: "other", summary: 12345, body: "B" } },
    { label: "Summary is non-string (array)", payload: { category: "other", summary: ["foo"], body: "B" } },
    { label: "Missing body and description", payload: { category: "other", summary: "S" } },
    { label: "Body and description are empty strings", payload: { category: "other", summary: "S", body: "", description: "" } },
    { label: "Body is whitespace only", payload: { category: "other", summary: "S", body: "   \n\t  " } },
    { label: "Missing category property", payload: { summary: "S", body: "B" } },
    { label: "Category is empty string", payload: { category: "", summary: "S", body: "B" } },
    { label: "Category is whitespace only", payload: { category: "   ", summary: "S", body: "B" } },
    { label: "Category is non-string (number)", payload: { category: 999, summary: "S", body: "B" } }
  ];

  for (const { label, payload } of missingFieldCases) {
    await test(`Field validation rejection (${label}) returns 400`, async () => {
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 400);
      const json = await res.json();
      assert.ok(json.error, "Should have error message");
    });
  }

  // =========================================================================
  // SECTION 4: Category Taxonomy & Unknown Category Slugs
  // =========================================================================
  console.log("\n--- Section 4: Category Taxonomy & Unknown Categories ---");

  const unknownCategories = [
    "unknown",
    "UNKNOWN_CATEGORY",
    "security-exploit",
    "crash-app",
    "null",
    "1234",
    "random-string-not-in-map"
  ];

  for (const cat of unknownCategories) {
    await test(`Unknown category slug '${cat}' returns 400`, async () => {
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: cat, summary: "Valid summary", body: "Valid body" })
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 400);
      const json = await res.json();
      assert.ok(json.error.includes("Invalid category"));
    });
  }

  const validCategoryVariations = [
    { input: "filter-tour", expectedSlug: "filter-tour", expectedDisplay: "Filter / Tour Mismatch" },
    { input: "FILTER_TOUR_MISMATCH", expectedSlug: "filter-tour", expectedDisplay: "Filter / Tour Mismatch" },
    { input: "  filter / tour mismatch  ", expectedSlug: "filter-tour", expectedDisplay: "Filter / Tour Mismatch" },
    { input: "score-live-data", expectedSlug: "score-live-data", expectedDisplay: "Score / Live Data" },
    { input: "SCORE_LIVE_DATA", expectedSlug: "score-live-data", expectedDisplay: "Score / Live Data" },
    { input: "watch-highlights", expectedSlug: "watch-highlights", expectedDisplay: "Watch / Highlights" },
    { input: "WATCH_HIGHLIGHTS", expectedSlug: "watch-highlights", expectedDisplay: "Watch / Highlights" },
    { input: "home-widget", expectedSlug: "home-widget", expectedDisplay: "Home Widget" },
    { input: "HOME_WIDGET", expectedSlug: "home-widget", expectedDisplay: "Home Widget" },
    { input: "ui-layout", expectedSlug: "ui-layout", expectedDisplay: "UI / Layout" },
    { input: "UI_LAYOUT", expectedSlug: "ui-layout", expectedDisplay: "UI / Layout" },
    { input: "other", expectedSlug: "other", expectedDisplay: "Other" },
    { input: "OTHER", expectedSlug: "other", expectedDisplay: "Other" }
  ];

  for (const { input, expectedSlug, expectedDisplay } of validCategoryVariations) {
    await test(`Valid category variation '${input}' maps to slug '${expectedSlug}' and title prefix '[${expectedDisplay}]'`, async () => {
      let sentBody = null;
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (url, opts) => {
        sentBody = JSON.parse(opts.body);
        return new Response(JSON.stringify({ number: 888, html_url: "issue-url" }), { status: 201 });
      };

      try {
        const req = new Request("https://worker.dev/bug-report", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ category: input, summary: "Test issue", body: "Body" })
        });
        const res = await worker.fetch(req, validTokenEnv, {});
        assert.strictEqual(res.status, 201);
        assert.ok(sentBody.title.startsWith(`[${expectedDisplay}]`));
        assert.deepStrictEqual(sentBody.labels, ["bug", "in-app-report", expectedSlug]);
      } finally {
        globalThis.fetch = originalFetch;
      }
    });
  }

  // =========================================================================
  // SECTION 5: Length Clamping & Boundary Truncation
  // =========================================================================
  console.log("\n--- Section 5: Payload Sanitization & Length Clamping ---");

  await test("Excessive summary (>500 chars) is safely truncated to 500 chars", async () => {
    let sentBody = null;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      sentBody = JSON.parse(opts.body);
      return new Response(JSON.stringify({ number: 777, html_url: "issue-url" }), { status: 201 });
    };

    try {
      const hugeSummary = "A".repeat(1200);
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: "other", summary: hugeSummary, body: "Short body" })
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 201);
      // Title is `[Other] ` + summary(sliced to 500)
      const expectedTitleLength = "[Other] ".length + 500;
      assert.strictEqual(sentBody.title.length, expectedTitleLength);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  await test("Excessive body (>65536 chars) is safely truncated to 65536 chars", async () => {
    let sentBody = null;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      sentBody = JSON.parse(opts.body);
      return new Response(JSON.stringify({ number: 666, html_url: "issue-url" }), { status: 201 });
    };

    try {
      const hugeBody = "X".repeat(100000);
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: "other", summary: "Short summary", body: hugeBody })
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 201);
      assert.strictEqual(sentBody.body.length, 65536);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  // =========================================================================
  // SECTION 6: Downstream GitHub Error Handling & Graceful Fallback
  // =========================================================================
  console.log("\n--- Section 6: Downstream GitHub Error Handling ---");

  const downstreamErrorCases = [
    { status: 401, desc: "Bad GitHub credentials (HTTP 401)", expectedReason: "GITHUB_API_ERROR" },
    { status: 403, desc: "GitHub secondary rate limit / forbidden (HTTP 403)", expectedReason: "GITHUB_API_ERROR" },
    { status: 422, desc: "GitHub validation error (HTTP 422)", expectedReason: "GITHUB_API_ERROR" },
    { status: 500, desc: "GitHub server internal error (HTTP 500)", expectedReason: "GITHUB_API_ERROR" },
    { status: 503, desc: "GitHub service unavailable (HTTP 503)", expectedReason: "GITHUB_API_ERROR" }
  ];

  for (const { status, desc, expectedReason } of downstreamErrorCases) {
    await test(`GitHub downstream ${desc} returns HTTP 502 with fallback_required: true`, async () => {
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => new Response(JSON.stringify({ message: `GitHub error ${status}` }), {
        status: status,
        headers: { "Content-Type": "application/json" }
      });

      try {
        const req = new Request("https://worker.dev/bug-report", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ category: "other", summary: "Summary", body: "Body" })
        });
        const res = await worker.fetch(req, validTokenEnv, {});
        assert.strictEqual(res.status, 502, `Expected 502 for GitHub error ${status}, got ${res.status}`);
        const json = await res.json();
        assert.strictEqual(json.success, false);
        assert.strictEqual(json.fallback_required, true);
        assert.strictEqual(json.reason, expectedReason);
        assert.strictEqual(json.status_code, status);
      } finally {
        globalThis.fetch = originalFetch;
      }
    });
  }

  await test("Network throw during GitHub fetch returns 502 with RELAY_NETWORK_ERROR and fallback_required: true", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
      throw new Error("DNS resolution failed for api.github.com");
    };

    try {
      const req = new Request("https://worker.dev/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: "other", summary: "Summary", body: "Body" })
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 502);
      const json = await res.json();
      assert.strictEqual(json.success, false);
      assert.strictEqual(json.fallback_required, true);
      assert.strictEqual(json.reason, "RELAY_NETWORK_ERROR");
      assert.ok(json.error.includes("DNS resolution failed"));
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  // =========================================================================
  // SECTION 7: HTTP Verbs and Route Aliases
  // =========================================================================
  console.log("\n--- Section 7: HTTP Verbs and Route Aliases ---");

  const disallowedVerbs = ["GET", "PUT", "DELETE", "PATCH"];
  for (const verb of disallowedVerbs) {
    await test(`${verb} /bug-report returns HTTP 405 Method Not Allowed with CORS headers`, async () => {
      const req = new Request("https://worker.dev/bug-report", { method: verb });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 405);
      assert.strictEqual(res.headers.get("Access-Control-Allow-Origin"), "*");
      const json = await res.json();
      assert.ok(json.error.includes("Method Not Allowed"));
    });
  }

  await test("POST /api/bug-report works identically to POST /bug-report", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ number: 555, html_url: "alias-url" }), { status: 201 });

    try {
      const req = new Request("https://worker.dev/api/bug-report", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category: "watch-highlights", summary: "Alias test", body: "Body" })
      });
      const res = await worker.fetch(req, validTokenEnv, {});
      assert.strictEqual(res.status, 201);
      const json = await res.json();
      assert.strictEqual(json.success, true);
      assert.strictEqual(json.issue_number, 555);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  // =========================================================================
  // SUMMARY
  // =========================================================================
  console.log("\n================================================================");
  console.log(`STRESS RESULTS: ${passed} passed, ${failed} failed`);
  console.log("================================================================");

  if (failed > 0) {
    process.exit(1);
  }
}

runAdversarialSuite();
