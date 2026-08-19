# clele-print-daemon

Prints labels pushed from the Clele web app to a label printer, without any user interaction. Runs
as a systemd service on Ubuntu. No JVM, no Python venv — a single static Go binary.

Two printer families are supported, picked per daemon in the web app:

- **Brother QL** on the network — status over IPP, raster over raw TCP port 9100.
- **Dymo LabelWriter** on USB, through the machine's own **CUPS** queue — IPP for everything.

### Prerequisite for a USB printer

The Dymo path needs CUPS and the vendor driver on this machine, with the printer installed as a
queue:

```
sudo apt install cups printer-driver-dymo
lpstat -p                 # the queue name is what you select in the web app
```

Nothing else: the daemon submits over IPP to `localhost:631`, so it needs **no udev rule and no
group membership** and keeps running as the unprivileged `clele-print` user the installer creates.
CUPS does the device-specific work, so no vendor protocol is reimplemented here.

## Build

```
cd daemon
go build -o clele-print-daemon ./cmd/clele-print-daemon
```

Cross-compile for a different target if needed, e.g. `GOOS=linux GOARCH=amd64 go build ...`.

## Versioning

The daemon reports a version (`YYYYMMDD.HHMMSS`) that the web app compares against the version it
expects, warning on the Settings page when a daemon is out of date. The version is the timestamp of
the **last commit that touched `daemon/`**, so it changes only when the daemon actually changes —
not on every build. The Maven backend build computes it and injects it via
`-ldflags "-X main.version=…"`; the same value is written into the app as the expected version.

A plain `go build` (as above) leaves the version as `unknown`, and the app shows no warning for a
daemon reporting an unknown version rather than a bogus one. Uncommitted changes to `daemon/` do
not move the version — commit them, then rebuild. Check a binary's version with:

```
clele-print-daemon version
```

## Install

Copy `clele-print-daemon` (the built binary), `install.sh`, and `clele-print-daemon.service` onto
the target Ubuntu machine (same directory), then:

```
sudo ./install.sh --backend-url https://your-clele-server
```

This installs the binary to `/usr/local/bin`, creates a dedicated `clele-print` system user,
self-registers with the backend (writing `/etc/clele-print-daemon/config.json`, `chmod 600`), and
installs + starts the `clele-print-daemon` systemd service.

The daemon self-registers as **pending** — it isn't usable until claimed. Open the Clele web app
from a browser on the same network as this machine, go to **My Account → Label printing**, and
claim it (only daemons seen from your browser's current network IP are listed). Then pick the
printer type there, and either the printer's IP address (Brother) or the CUPS queue and the loaded
label size (Dymo).

## How it works

- Long-polls `GET /api/daemon/jobs/next` (backend, authenticated via the API key issued at
  registration) for queued print jobs.
- Reads printer state and the media geometry over **IPP** (port 631, `internal/ipp`) — from the
  printer itself for a Brother, from the local CUPS queue for a Dymo.
- Decodes the label PNG and hands it to the driver for the configured printer family
  (`internal/printer` is the seam): `internal/qlraster` builds the Brother QL raster command stream
  and writes it to port 9100; `internal/cupsprint` builds a CUPS raster and submits it to the
  queue with IPP `Print-Job`.
- Reports the printable area it derived, so the web app can size labels without duplicating any
  printer geometry of its own.
- Reports success/failure back via `POST /api/daemon/jobs/{id}/complete`, including the printer's
  own reason for a failure.

### Why status comes from IPP, not the raster protocol

The Brother raster protocol defines a status command (`ESC i S`) that returns a 32-byte packet.
The QL-710W's port 9100 is **write-only** — verified against the hardware, it never answers that
request, with or without an invalidate/initialize preamble. A job written there therefore
"succeeds" even while the printer is flashing an error.

IPP on port 631 does answer, and reports both the fault and the media actually loaded:

```
printer-state         = stopped
printer-state-reasons = other-error
media-ready           = om_brother-label-17x54mm_17x54mm
```

The daemon uses this to refuse printing when the printer is faulted (reporting the real reason
back to the web app) and to declare the correct media in the job. On a Brother, media is
**detected, never configured** — a job must declare the media kind (continuous tape vs die-cut
labels), width, and for die-cut its fixed length; declaring anything else is rejected as a media
error. Detected media is reported to the app on every poll, so changing the label roll needs no
user action.

A **Dymo LabelWriter cannot sense its roll at all**, so there the label size is picked in the web
app instead and sent with every job. The daemon reports the sizes the queue offers so the app can
show them as a list.

Check what a printer reports at any time:

```
clele-print-daemon status --printer-ip 192.168.1.56     # a network Brother
clele-print-daemon status --queue DYM0010               # a CUPS printer
clele-print-daemon status                                # just list this machine's queues
```

### Print geometry (measured, not derived)

The raster encoding (`internal/qlraster/raster.go`) follows the publicly documented Brother QL
protocol. Three constants were measured on a QL-710W with 17×54mm die-cut labels, because they are
physical properties that cannot be computed from the media size:

| constant | value | how it was determined |
|---|---|---|
| horizontal offset | **dot 0** (left-aligned) | printed bands from different head zones; only the zone at dots 0–200 landed on the label |
| leading feed | **~6mm** | measured gap before printed content on a full-width test print |
| unreachable edge | **~2mm** | measured blank strip across the head on a full-width test print |

Media is **left-aligned on the print head, not centred**. Centring content placed a 17mm label's
data at dots 280–440 — entirely off the label — which produced correctly-sized, correctly-cut, but
completely blank labels. `TestDieCutGeometryMatchesHardware` locks this in.

Die-cut jobs must emit exactly `printableLines(lengthMm)` raster lines: too few and the printer
cuts the label short (a 400-line job on a 54mm label cut at 44mm), too many overruns.

These constants are mirrored in the frontend (`frontend/src/utils/labelPrint.ts`), which renders
labels to the printable area so nothing is clipped — keep the two in sync. If you use a different
QL model and labels come out misaligned, re-measure with a full-width black test print.

## Useful commands

```
systemctl status clele-print-daemon
journalctl -u clele-print-daemon -f
```
