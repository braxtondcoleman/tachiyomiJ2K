# Phase I — intuitive manga UI, reader recovery and website verification

## Goal

Everyday manga reading should be intuitive:
**Library → Manga details → Chapters → Reader**. If a page fails, present a direct recovery action without forcing the reader to navigate technical Settings.

### Implemented interface

- **Library toolbar → Explore & organize**, a themed icon-based sheet with recommendations, library health/duplicate review, extension diagnostics and a short usage guide.
- **Manga details → Explore & fix manga**, a themed, context-aware sheet with chapter versions, finding other sources, confirmed links, related recommendations and fixes. Individual historical menu items remain internally addressable but are hidden to reduce clutter.
- Both menus preserve native J2K theme and use standard iconography; local and unsaved manga omit actions that require unavailable remote/source links.
- **Reader page error** now explains the failure and offers: Retry page, Verify website, Try another source, and Open in WebView when available. Touch controls use at least 48dp height.
- A manga-specific source fallback remains opt-in and source-specific.

### Cloudflare: automatic first, interactive only when needed

J2K already attempts Cloudflare handling invisibly through its interceptor using a temporary WebView and a shared Android CookieManager. Phase I **does not** fake checkbox clicks or bypass CAPTCHA verification.

- The interceptor detects 403/503 responses with Cloudflare server or \`cf-mitigated: challenge\` evidence.
- The invisible automatic attempt runs first. **Only if it fails**, a throttled, host-only signal tells the foreground reader to offer an interactive in-app browser sheet for that source.
- The sheet opens the installed source's HTTPS website/chapter URL. A URL with external host, credentials, unsafe scheme or nonstandard port is rejected or falls back to the source home, and the WebView has no privileged JavaScript bridge or file access.
- The reader lets a person finish a Cloudflare checkbox/login if required. A *new* \`cf_clearance\` cookie triggers automatic sheet close + a retry. If the website uses another authentication mechanism, the user can explicitly select **Done & retry**.
- The same CookieManager is used by the app's network layer. Cancel does nothing. A 120-second cooldown per site reduces repeated prompts from prefetch failures, and the overlay won't open for unrelated site background updates.
- Page error buttons retry the **same page's own page loader** in pager and webtoon. Automatic failure prompts retry the current errored page or reload the errored chapter, preserving reader location and reading history.
- This is not guaranteed to solve every website's challenge: Cloudflare can block embedded browsers, challenge policies differ, and expired/missing cookies may require re-verification.

### Regression tests

- Context-aware manga action menu for favorite, unsaved, local and duplicate choices.
- Distinguish Cloudflare errors from generic 403 and 429, HTTPS host/cookie safety, same-host restrictions and a genuine new clearance requirement.

### Release gates

- [ ] Latest Phase I JVM tests and Android debug compilation pass
- [ ] Same-key disposable-signer v114→115 emulator upgrade preserves app-private data
- [ ] Physical reader page error, visible checkbox, login flow, successful return to failed page, manual cancel, repeated/changing source, expired cookie and webtoon/pager verification
- [ ] Verify menus, accessibility, source navigation and locale/theme on phone
- [ ] Permanent user-controlled signing key and backup/restore before long-term release

## Fork feature inventory (not a claim of everything being ported)

Phase I focuses on flow and navigation. Not all TachiyomiSY/Komikku/fork options are present. Remaining optional items include tracker-backed recommendations, richer saved searches, source/language badges, customizable theme palettes, advanced category hiding, reader autoscroll and fine-grained personalization. Existing native J2K capabilities should be inspected before duplicating them. The list is an audit candidate, not a verified gap inventory.
