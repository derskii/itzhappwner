# Happwner API 102 and subscription identity review

Checked: 2026-10-18. Scope: verify native libxposed API 102 integration requirements, the Android floor and lifecycle callbacks, Happ's public subscription-header documentation versus the XTLS device-info proposal, and source-backed User-Agent patterns from open client repositories. No target subscription endpoints were contacted.

## Decision question and hypotheses

Can Happwner migrate to native libxposed API 102 while retaining its advertised Android 5 support, and are `x-os`, `x-ver`, and `x-device-model` documented Happ request headers separate from `User-Agent`?

- H1 — Native API 102 uses the legacy `assets/xposed_init` / `de.robv.android.xposed` entry path. **Refuted:** modern modules use `XposedModule`, `META-INF/xposed/java_init.list`, and module properties; API 102 targeting disallows legacy API calls.
- H2 — API 102's supported Android floor is below API 26. **Refuted:** the current API module build declares `minSdk = 26`.
- H3 — Happ's docs establish `x-os`, `x-ver`, and `x-device-model` semantics. **Under-determined / not established:** Happ documents changing `User-Agent`; the separate XTLS/Xray proposal names optional `x-device-os`, `x-ver-os`, and `x-device-model` fields.
- H4 — Common client User-Agent strings can be copied as fixed current versions. **Refuted:** source strings use dynamic version fields and custom overrides; do not hard-code versions from memory.

## Sourcing and evidence

Primary sources only: current libxposed maintainer repository, current Vector framework implementation, LSPosed developer documentation, Happ's developer documentation and project README, the XTLS/Xray discussion proposal, and official client source repositories. Each source file below contains short verbatim excerpts and provenance. Credibility, recency, and bias-risk scores are in `sources.csv` (bias risk: low / moderate / high).

## Risk register and stop criteria

- **Android compatibility:** API 102 library targets API 26+, and Vector's current implementation dispatches `onPackageReady` from API 28 and `onPackageLoaded` from API 29. Do not promise working target hooks on API 26–27 from those lifecycle callbacks.
- **Migration completeness:** `XposedModule` and interceptor hooks replace the legacy module entry and callback model. A dependency swap alone does not provide compatibility.
- **Identity headers:** the XTLS page is a proposal/discussion, not Happ's protocol contract. Treat the device headers as optional proposal fields unless Happ itself documents or confirms them.
- **Versions:** UA tokens should use installed/runtime app version where available. No fixed version values were inferred.
- **Fork terms:** the project's README states non-commercial use and attribution conditions. Recheck before distributing or commercializing a fork.

Stop criteria met for this bounded review: official API build metadata and signatures located; official Happ docs distinguished from the XTLS proposal; sourced UA patterns captured where open client code exposed them. Closed-source clients and unexpanded NekoBox fallback UA remain unresolved rather than guessed.

## Refresh triggers

Refresh if libxposed publishes a newer API artifact or changes `minSdk`; if Vector changes lifecycle dispatch for API 26–28; if Happ documents request identity headers; if Hiddify/v2rayNG changes UA templates; or before public/commercial distribution of this fork.
