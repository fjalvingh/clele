# Label Printing

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## Label Printing

Two delivery methods, chosen per user (`app_user.print_method`, `PrintMethod` BROWSER/DAEMON):

- **BROWSER** (default, unchanged): `components/PrintLabelModal.tsx` builds a self-contained HTML
  document and prints it via a hidden iframe. Fixed 50×18mm (`LABEL_W_MM`/`LABEL_H_MM`); the user
  picks the printer in the OS dialog. No backend involvement.
- **DAEMON**: silent printing to a network label printer via the Go daemon in `daemon/`. The
  browser renders the label to a PNG on a canvas, POSTs it as a job, and the daemon long-polls for
  it and drives the printer.

### Barcode labels

A part can additionally be labelled with a **Code 128** barcode identifying it *in this app*:
`CLE-` + the zero-padded part id (`CLE-000123`). The prefix is the whole point — it makes our label
distinguishable from a manufacturer/distributor barcode, so `pages/BarcodeScanner.tsx` `handleScan`
can `parsePartBarcode` it and go straight to the part, skipping the pg_trgm fuzzy match, the AI web
search *and* the rescan-dedupe guard. Anything without the prefix takes the old path unchanged.

- **`frontend/src/utils/code128.ts`** is the only implementation: `partBarcode` / `parsePartBarcode`,
  the subset-B encoder `code128bModules` (start 104, checksum `(104 + Σ i·value) % 103`, 13-module
  stop), and both renderers — `drawCode128` (canvas, daemon path) and `code128Svg` (inline SVG,
  browser path). One module list feeds both so they cannot drift.
- **Bars must survive a 1-bit 300 dpi raster** (the daemon thresholds, it does not dither): the
  module width is always an **integer number of device dots**, 2–4, chosen by `pickModuleWidth` from
  the available width, with a ≥10-module quiet zone each side. Too narrow for even 2 dots ⇒ the
  label falls back to the code as text and the modal says so (`barcodeFits` /
  `barcodeModuleWidthMm` in `utils/labelPrint.ts`).
- **It is a separate label**, never mixed with the text label. Browser path:
  `PrintLabelModal.labelDocument([...])` emits one page per label, so two labels come out of a
  single print dialog. Daemon path: two sequential jobs (`printLabelViaDaemon` then
  `printBarcodeLabelViaDaemon`, both via the shared `sendPngToDaemon`); the second is skipped if the
  first fails.
- Opt-in per print via a checkbox in `PrintLabelModal`, defaulted from and saved back to
  `app_user.print_barcode_label` (V35) through the existing `PUT /api/profile/printing`; the same
  checkbox is on My Account. Nothing changed in `PrintJob`, the daemon protocol or the Go daemon —
  a barcode label is just another PNG.

### Job flow (daemon path)

1. Daemon self-registers unauthenticated → `POST /api/daemon/register` → PENDING, no owner; the
   raw API key is returned **once** (BCrypt hash stored).
2. User claims it on **My Account → Label printing**. Only daemons whose `last_seen_ip` matches the
   *browser's current request IP* are listed, and a claimed daemon is restricted to its owner
   (see `PrintDaemonService.listForCurrentUserAtIp` / `findVisible`).
3. Daemon long-polls `GET /api/daemon/jobs/next?wait=25`. Backend replies 200 + job or 204, and
   **always** sets `X-Printer-Ip` so the daemon can query the printer before any job exists.
4. Browser renders the PNG (`utils/labelPrint.ts`) → `POST /api/print-jobs` → `PrintJobService`
   persists it and completes a `CompletableFuture` to wake a waiting daemon immediately.
5. Daemon prints, then `POST /api/daemon/jobs/{id}/complete` with DONE/FAILED + the printer's own
   error text, which the UI shows.

`PrintJobService.nextForDaemon` is deliberately **not** `@Transactional` — it blocks for the whole
wait window and must not hold a DB connection idle.

### Auth for daemons

`/api/daemon/**` has its **own** `SecurityFilterChain` (`@Order(1)`, `securityMatcher`) in
`SecurityConfig`, authenticated by `DaemonApiKeyAuthFilter` reading `X-Daemon-Id` + `X-Daemon-Key`
(stateless, no session). `/api/daemon/register` is `permitAll`. The session-cookie chain
(`@Order(2)`) is unchanged and still covers everything else.

### Daemon versioning

The daemon version (`YYYYMMDD.HHMMSS`) is the **commit timestamp of the last commit touching
`daemon/`**, so it changes only when the daemon actually changes — not per build. Computed once by
`maven-antrun-plugin` (`daemon-version` execution, `initialize` phase, `exportAntProperties`), then
(a) baked into the binary via `-ldflags -X main.version=…` and (b) written to `daemon-version.txt`
on the backend classpath as the *expected* version (`DaemonVersionService`). Daemons report theirs
via `X-Daemon-Version` on **every** call, so an in-place binary swap is reflected without
re-registration; My Account warns when they differ. Uncommitted `daemon/` changes do **not** move
the version — commit first. Unknown on either side ⇒ no warning, never a bogus one.

### Printer communication — IMPORTANT for adding printers

Currently supports **Brother QL-series network printers** (developed against a QL-710W). Two
separate channels, and the split matters:

- **Status + media detection: IPP, port 631** (`internal/ipp`, minimal stdlib Get-Printer-Attributes
  client). Reads `printer-state`, `printer-state-reasons`, `media-ready`.
- **Printing: raw TCP, port 9100** (`internal/qlraster`), Brother raster command stream.

**Port 9100 on the QL-710W is write-only** — verified against hardware, it never answers the
raster protocol's own status request (`ESC i S`), with or without an invalidate/initialize
preamble. A job written there therefore "succeeds" even while the printer flashes an error. That
is why status comes from IPP instead; `qlraster.Print` checks IPP *before* printing (refusing when
faulted, reporting the real reason) and again after.

**Media is detected, never configured.** A Brother QL job must declare the media in its
print-information command (`ESC i z`) — kind (continuous vs die-cut), width, and for die-cut the
fixed length. Declaring anything other than what is loaded is rejected as a media error (flashing
red LED, generic "ERROR"). The daemon reads it from IPP and reports it to the backend via
`X-Printer-Media-*` headers on every poll (throttled to ~1/min), stored on `print_daemon`
(`media_kind`/`media_width_mm`/`media_length_mm`/`media_name`, V34) and shown read-only in the UI.
Changing the roll needs no user action. An earlier manual "tape width" setting was removed
precisely because it could not describe die-cut labels and went stale.

**Print geometry is measured, not derived** (`internal/qlraster/raster.go` constants). These are
physical printer properties that cannot be computed from the media size:

| constant | value (QL-710W) | how determined |
|---|---|---|
| `mediaOffsetDots` | `0` — **left-aligned** | printed bands from different head zones; only dots 0–200 landed on a 17mm label |
| `dieCutLeadMm` | `6.0` | measured gap before content on a full-width test print |
| `unprintableEdgeMm` | `2.0` | measured blank strip across the head |

Media is **left-aligned on the 720-dot head, not centred**. Centring put a 17mm label's content at
dots 280–440 — off the label — giving correctly-sized, correctly-cut, *blank* labels. Die-cut jobs
must emit exactly `printableLines(lengthMm)` lines; too few and the printer cuts short (400 lines
on a 54mm label cut at 44mm), too many overruns. `TestDieCutGeometryMatchesHardware` locks all of
this in.

Also easy to get wrong (all previously were): `ESC i M` is **various mode settings** (`0x40` =
auto-cut), *not* compression; compression is the standalone `M` command (`0x4D 0x00` = none) and
must be sent or the printer decodes raster with leftover state; `ESC i K` `0x08` = cut at end.

### Adding another printer type or label type

- **Another label size on a Brother QL**: usually nothing to do — size comes from IPP. Verify with
  a full-width black test print; if it is misaligned, re-measure the three geometry constants.
- **A non-Brother printer**: `internal/ipp` is vendor-neutral and should be reused for status/media.
  The raster encoding is Brother-specific — introduce a printer-driver abstraction (e.g. an
  interface with `BuildCommands(png, media)` + a transport) and select on the IPP-reported
  make/model rather than adding conditionals inside `qlraster`.
- **Geometry constants are mirrored** in `frontend/src/utils/labelPrint.ts` (`labelSizeFor` renders
  to the *printable* area so nothing clips) — keep the two in sync, as with `MetricUnitParser`/
  `units.ts`.
- **Diagnosing a printer**: `clele-print-daemon status --printer-ip <ip>` prints state, media and
  the raw IPP media keyword. For alignment problems, print a full-width black label and measure the
  margins — that is how the current constants were established (see `daemon/README.md`).
