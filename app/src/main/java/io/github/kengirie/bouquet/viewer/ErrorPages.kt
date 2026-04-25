package io.github.kengirie.bouquet.viewer

/**
 * Renders the polished dark-themed error HTML pages served by the
 * loopback gateway. Direct port of `hintForStatus` / `renderErrorHtml`
 * from desktop `electron/session-registry.ts`.
 *
 * All user-influenced strings ([detail], [path], [statusText]) are HTML
 * escaped before reaching the page. Only system fonts are referenced —
 * the page is served from `127.0.0.1:<ephemeral>` and the WebView has no
 * outbound network for remote stylesheets / fonts (and we wouldn't want
 * to call out anyway, for privacy and offline behavior).
 */

internal fun hintForStatus(status: Int): String = when (status) {
    400 -> "The address you used is invalid."
    404 -> "The site or the requested path doesn't exist on Nostr."
    405 -> "That HTTP method is not allowed on the viewer. Only GET and HEAD are supported."
    500 -> "Something went wrong inside the gateway."
    502 -> "Could not fetch the blob from any Blossom server. The file may be unavailable or all servers are unreachable."
    else -> "The gateway returned an error."
}

// The CSS lives as a constant so the renderErrorHtml body stays readable.
// It mirrors the polished desktop card layout: deep #111125 background, a
// rounded #1a1a2e card with #ffb2b7 accents, large status number with a
// glow text-shadow, uppercase tracking-wide statusText, hint paragraph,
// dimmer detail paragraph, and an optional monospace path block.
private val ERROR_PAGE_CSS = """
  html, body { margin: 0; padding: 0; height: 100%; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    background: #111125;
    color: #e2e0fc;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 2rem;
    box-sizing: border-box;
  }
  .card {
    max-width: 640px;
    width: 100%;
    background: #1a1a2e;
    border: 1px solid rgba(255, 178, 183, 0.25);
    border-radius: 16px;
    padding: 3rem 2.5rem;
    box-shadow: 0 30px 80px rgba(0, 0, 0, 0.45);
    text-align: center;
  }
  .status {
    font-size: 6rem;
    font-weight: 700;
    letter-spacing: -0.05em;
    color: #ffb2b7;
    line-height: 1;
    text-shadow: 0 0 40px rgba(255, 178, 183, 0.25);
  }
  .statusText {
    margin-top: 0.5rem;
    font-size: 1.1rem;
    font-weight: 500;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #e0bec0;
  }
  .hint {
    margin-top: 2rem;
    font-size: 1.05rem;
    line-height: 1.55;
    color: #e2e0fc;
  }
  .detail {
    margin-top: 1.25rem;
    font-size: 0.85rem;
    line-height: 1.5;
    color: rgba(226, 224, 252, 0.6);
    word-break: break-word;
    white-space: pre-wrap;
  }
  .path {
    margin-top: 1.5rem;
    padding: 0.75rem 1rem;
    background: #0c0c1f;
    border: 1px solid rgba(255, 178, 183, 0.15);
    border-radius: 8px;
    font-size: 0.8rem;
    color: #ffb2b7;
    word-break: break-all;
    text-align: left;
    font-family: "SFMono-Regular", Menlo, Consolas, ui-monospace, monospace;
  }
  .path code {
    font-family: inherit;
  }
""".trimIndent()

internal fun renderErrorHtml(
    status: Int,
    statusText: String,
    detail: String,
    hint: String? = null,
    path: String? = null,
): String {
    val safeStatus = status.toString()
    val safeStatusText = escapeHtml(statusText)
    val safeDetail = escapeHtml(detail)
    val safeHint = escapeHtml(hint ?: hintForStatus(status))
    val pathBlock = if (!path.isNullOrEmpty()) {
        """<div class="path"><code>${escapeHtml(path)}</code></div>"""
    } else {
        ""
    }

    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>$safeStatus $safeStatusText</title>
<style>
$ERROR_PAGE_CSS
</style>
</head>
<body>
<div class="card">
  <div class="status">$safeStatus</div>
  <div class="statusText">$safeStatusText</div>
  <p class="hint">$safeHint</p>
  <p class="detail">$safeDetail</p>
  $pathBlock
</div>
</body>
</html>
"""
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
