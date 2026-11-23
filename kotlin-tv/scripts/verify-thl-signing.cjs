#!/usr/bin/env node
"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const assetPath = path.join(__dirname, "../app/src/main/assets/thl_signing.js");
const source = fs.readFileSync(assetPath, "utf8");
const cryptoShim = {
  getRandomValues(view) {
    const bytes = crypto.randomBytes(view.byteLength);
    for (let i = 0; i < view.byteLength; i++) view[i] = bytes[i];
    return view;
  },
};
const sandbox = {
  crypto: cryptoShim,
  console,
  URL,
  URLSearchParams,
  TextEncoder,
  TextDecoder,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  atob,
  btoa,
};
sandbox.window = sandbox;
sandbox.self = sandbox;
sandbox.global = sandbox;
sandbox.location = new URL("https://localhost/");
vm.createContext(sandbox);

// Run without Node's require/module/Buffer globals, matching the Android WebView host.
vm.runInContext(source, sandbox, { timeout: 30000 });

const device = {
  ac: "wifi",
  channel: "update_64",
  aid: "8662",
  app_name: "novelread",
  version_code: "73932",
  version_name: "7.3.9.32",
  device_platform: "android",
  os: "android",
  ssmix: "a",
  device_type: "25053RT47C",
  device_brand: "Redmi",
  language: "zh",
  os_api: "36",
  os_version: "16",
  manifest_version_code: "73932",
  resolution: "1280*2772",
  dpi: "520",
  update_version_code: "73932",
  host_abi: "arm64-v8a",
  dragon_device_type: "phone",
  pv_player: "73932",
  compliance_status: "0",
  need_personal_recommend: "1",
  player_so_load: "1",
  is_android_pad_screen: "0",
  iid: "6543210123456789012",
  device_id: "1234567890123456789",
  cdid: "a2d9e4c7-1c2b-4f66-8d1a-123456789abc",
};
const body = Buffer.from(JSON.stringify({
  biz_param: {
    detail_page_version: 0,
    device_level: 3,
    disable_digg_stat: false,
    need_all_video_definition: true,
    need_mp4_align: false,
    use_os_player: false,
    use_server_dns: false,
    video_platform: 1024,
  },
  mixed_video_id_map: { 1004: ["7701937936731737112"] },
}));
const now = 1795430400123;
const signed = sandbox.__hongguoSignPlayback(
  "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",
  body.toString("base64"),
  device,
  now,
);

assert.ok(signed && typeof signed.url === "string");
const url = new URL(signed.url);
assert.equal(url.hostname, "api5-normal-sinfonlineb.fqnovel.com");
assert.equal(url.searchParams.get("device_id"), device.device_id);
assert.equal(url.searchParams.get("iid"), device.iid);
assert.equal(url.searchParams.get("device_brand"), "Redmi");
assert.equal(url.searchParams.get("version_code"), "73932");
assert.equal(url.searchParams.get("ts"), String(Math.floor(now / 1000)));
assert.ok(Number(url.searchParams.get("_rticket")) >= now);
for (const name of ["x-khronos", "x-gorgon", "x-ladon", "x-argus", "x-helios", "x-medusa", "x-ss-stub"]) {
  assert.equal(typeof signed.headers[name], "string", "missing " + name);
  assert.ok(signed.headers[name].length > 0, "empty " + name);
}
assert.equal(signed.headers["x-khronos"], String(Math.floor(now / 1000)));
assert.equal(signed.headers["x-ss-stub"], crypto.createHash("md5").update(body).digest("hex").toUpperCase());
assert.ok(Buffer.from(signed.headers["x-medusa"], "base64").length > 40);
assert.ok(Buffer.from(signed.headers["x-helios"], "base64").length > 20);
console.log("THL N-line browser-host signing smoke test passed");
