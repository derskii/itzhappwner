# S8 — XTLS/Xray subscription headers discussion

- URL: https://github.com/XTLS/Xray-core/discussions/4877
- Source type: primary Xray-core project discussion containing a community proposal; not a ratified Happ specification.
- Scores: credibility 4/5 for the proposal text; recency 4/5; bias risk moderate (proposal authors state design intent; open community discussion includes opposing views).
- Verbatim excerpts: “x-device-os”; “x-ver-os”; “x-device-model”; “The `User-Agent` header identifies the client application”.

The proposal names optional fields `x-device-os` (device OS), `x-ver-os` (OS version), and `x-device-model` (model name/identifier). It distinguishes them from `User-Agent`, which identifies the requesting client app. The shorthand keys `x-os` and `x-ver` are not the names in this source. Do not present these as Happ contract fields without Happ-specific evidence.
