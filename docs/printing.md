# Label Printing

Part of the Clele documentation — `CLAUDE.md` holds the overview and the index of these files; `API.md` lists the REST endpoints.

## Label Printing

Two delivery methods, chosen per user (`app_user.print_method`, `PrintMethod` BROWSER/DAEMON):

- **BROWSER** (default, unchanged): `components/PrintLabelModal.tsx` builds a self-contained HTML
  document and prints it via a hidden iframe. Fixed 50×18mm (`LABEL_W_MM`/`LABEL_H_MM`); the user
  picks the printer in the OS dialog. No backend involvement.
- **DAEMON**: silent printing to a label printer via the Go daemon in `daemon/` — a network
  Brother QL or a USB Dymo LabelWriter, chosen per daemon (see Printer communication below). The
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
   **always** returns the printer configuration — `X-Printer-Type` plus `X-Printer-Ip` or
   `X-Printer-Queue` + `X-Printer-Media` — so the daemon can probe the printer before any job
   exists. It also reports discovered queues via `POST /api/daemon/capabilities` (see below).
4. Browser renders the PNG (`utils/labelPrint.ts`) → `POST /api/print-jobs` → `PrintJobService`
   persists it and completes a `CompletableFuture` to wake a waiting daemon immediately.
5. Daemon prints, then `POST /api/daemon/jobs/{id}/complete` with DONE/FAILED + the printer's own
   error text, which the UI shows. The job carries its own printer configuration as well, so a
   configuration change racing a queued job cannot print it with settings it was not formatted for.

An unconfigured daemon is rejected at step 4 with a 400 naming what is missing, rather than
queueing a job that can only fail — the modal shows that immediately instead of timing out.

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

Two printer families are supported, chosen per daemon on **My Account → Label printing**
(`print_daemon.printer_type`, `PrinterType`). The type decides both the transport and how the
daemon is addressed, because a USB printer has no IP at all:

| | `BROTHER_QL` | `DYMO_CUPS` |
|---|---|---|
| developed against | QL-710W, on the network | LabelWriter 320, on USB |
| addressed by | `printer_ip` | `printer_queue` (a CUPS destination name) |
| status + media | IPP, port 631 (`internal/ipp`) | IPP to `localhost:631/printers/<queue>` |
| printing | raw TCP port 9100, Brother raster (`internal/qlraster`) | IPP `Print-Job`, `application/vnd.cups-raster` (`internal/cupsprint`) |
| label size | detected from the printer | **chosen in the UI** — see below |

`internal/printer` is the seam: a `Driver` with `Probe`/`Capabilities`/`Print`, plus the `Target`
the backend pushes down on every poll. `driverFor` in `main.go` is the only place that maps a type
to an implementation.

**Port 9100 on the QL-710W is write-only** — verified against hardware, it never answers the
raster protocol's own status request (`ESC i S`), with or without an invalidate/initialize
preamble. A job written there therefore "succeeds" even while the printer flashes an error. That
is why status comes from IPP instead; the driver checks IPP *before* printing (refusing when
faulted, reporting the real reason) and again after.

### Media: detected on a Brother, configured on a Dymo

**A Brother QL detects its media, and it is never configured.** A job must declare the media in its
print-information command (`ESC i z`) — kind (continuous vs die-cut), width, and for die-cut the
fixed length. Declaring anything other than what is loaded is rejected as a media error (flashing
red LED, generic "ERROR"). The daemon reads it from IPP and reports it on every poll. Changing the
roll needs no user action. An earlier manual "tape width" setting was removed precisely because it
could not describe die-cut labels and went stale.

**A Dymo LabelWriter cannot sense its roll at all**, so for that family the label size *is*
configuration: the daemon reports the queue's `media-supported` list (with exact sizes and margins
from `media-col-database`), the user picks one on My Account, and it is stored as
`print_daemon.media_keyword` and sent as the `media` attribute on each `Print-Job`. This is the one
exception to the rule above, and it exists only because the hardware gives no alternative.

### Print geometry is reported by the daemon, not mirrored in the frontend

Each driver knows the area its printer can actually mark and reports it on every poll
(`X-Printer-Printable-Width` / `-Length` → `print_daemon.printable_*_mm` → `PrintDaemonDTO`).
`labelSizeFor` in `frontend/src/utils/labelPrint.ts` renders straight to those numbers; it keeps
the old Brother constants only as a fallback for a daemon too old to report them.

Where each driver gets them:

- **Brother** — measured on the hardware, not derived (`internal/qlraster/raster.go`). These are
  physical properties that cannot be computed from the media size:

  | constant | value (QL-710W) | how determined |
  |---|---|---|
  | `mediaOffsetDots` | `0` — **left-aligned** | printed bands from different head zones; only dots 0–200 landed on a 17mm label |
  | `dieCutLeadMm` | `6.0` | measured gap before content on a full-width test print |
  | `unprintableEdgeMm` | `2.0` | measured blank strip across the head |

  Media is **left-aligned on the 720-dot head, not centred**. Centring put a 17mm label's content
  at dots 280–440 — off the label — giving correctly-sized, correctly-cut, *blank* labels. Die-cut
  jobs must emit exactly `printableLines(lengthMm)` lines; too few and the printer cuts short (400
  lines on a 54mm label cut at 44mm), too many overruns. `TestDieCutGeometryMatchesHardware` locks
  all of this in.

- **Dymo** — derived exactly from the margins CUPS reports in `media-col`, in hundredths of a
  millimetre. Nothing to measure. For the 19.05 × 50.80 mm roll: margins left 1.44, right 1.02, top
  5.76, bottom 1.52 ⇒ **16.59 × 43.52 mm printable**.

Dimensions are fractional throughout (`NUMERIC` in the database, `BigDecimal` in Java) because Dymo
stock is sized in inches; whole millimetres would put a label edge half a millimetre out.

### The Dymo raster — three things that cost a wasted label each

All established by printing to the hardware. Do not re-derive them.

1. **Never send a PNG to CUPS.** `imagetoraster` rescales the image under *every* scaling option
   there is — `print-scaling=none`, `ppi=300`, `scaling=100`, `fitplot=false` all turned a
   193×489 px input into a 191×477 px raster. The barcode section above requires an integer number
   of device dots per Code 128 module, so any resampling corrupts it. The daemon builds the raster
   itself and submits `application/vnd.cups-raster`, which makes CUPS run only the vendor driver.
2. **Fill in the whole raster header.** CUPS raster v3 is uncompressed (the file is exactly
   `1800 + bytesPerLine*cupsHeight` bytes), and a header with only the obvious fields set *prints*
   — but then leaves `raster2dymolw` waiting forever: CUPS sits at "Printing page 1, 99% complete",
   the job never completes, and the label is never fed out far enough to tear off. The fields that
   were missing: `cupsNumColors` (must be 3), `cupsCompression` (must be 0),
   `cupsBorderlessScalingFactor` (must be 1.0), the float `cupsPageSize`/`cupsImagingBBox`, and
   `cupsPageSizeName` — the likeliest single culprit, derived from the PWG media keyword
   (`custom_0.75x2in_0.75x2in` → `0.75x2`). `TestRasterHeaderFieldsTheDriverNeeds` pins them.
3. **The axis mapping is a transpose *plus* a head-axis flip.** A label PNG's x axis is the feed
   direction and its y axis runs across the head (the Brother convention); CUPS raster is row =
   feed, column = across the head. A plain transpose builds a perfectly valid raster that prints
   **mirrored**, because the Dymo's column 0 is the opposite end of the head from the Brother's dot
   0. The correct mapping is `dst(col, row) = src(x=row, y=srcHeight-1-col)`, pinned by
   `TestRasterAxisMapping`. Keeping the correction inside the driver means one PNG serves both
   families and the frontend stays printer-agnostic.

Also easy to get wrong on the Brother (all previously were): `ESC i M` is **various mode settings**
(`0x40` = auto-cut), *not* compression; compression is the standalone `M` command (`0x4D 0x00` =
none) and must be sent or the printer decodes raster with leftover state; `ESC i K` `0x08` = cut at
end.

### Capabilities: why they are not on the poll

The queue list and each queue's media list run to dozens of entries, far too large for a response
header, and change only when the machine's printer setup does. The daemon therefore pushes them to
**`POST /api/daemon/capabilities`** (stored as `print_daemon.capabilities`, JSONB) at startup,
whenever its target changes, and whenever the backend asks by setting `X-Capabilities-Wanted` on a
poll. Without that header a daemon that had already reported once could never learn the backend had
dropped its capabilities, and the queue picker would stay empty until the daemon restarted.

Note the header naming: `X-Printer-Media` travels **down** (the configured keyword) while the
`X-Printer-Media-*` family travels **up** (what the daemon found). Same prefix, opposite directions.

### Adding another printer type or label type

- **Another label size on a Brother QL**: usually nothing to do — size comes from IPP. Verify with
  a full-width black test print; if it is misaligned, re-measure the three geometry constants.
- **Another CUPS-attached printer**: quite possibly nothing but a new `PrinterType` value, since
  `internal/cupsprint` is vendor-neutral — it asks CUPS for the geometry and lets the packaged
  driver do the device-specific work. Verify with a bordered test label before trusting it.
- **A printer with its own protocol**: implement `printer.Driver` in a new package and add a case to
  `driverFor`. Reuse `internal/ipp` for status; do not add conditionals inside `qlraster`.
- **Diagnosing a printer**: `clele-print-daemon status --printer-ip <ip>` for a Brother, or
  `clele-print-daemon status --queue <name>` for a CUPS printer — with no `--queue` it just lists the
  queues on the machine, which is what you want when you do not yet know the name to configure. For
  alignment problems print a bordered label and check all four edges are there (see
  `daemon/README.md`).
