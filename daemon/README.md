# clele-print-daemon

Prints labels pushed from the Clele web app to a network Brother QL-710W, without any user
interaction. Runs as a systemd service on Ubuntu. No JVM, no Python venv — a single static Go
binary.

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
claim it (only daemons seen from your browser's current network IP are listed). Then set the
printer's IP address there.

## How it works

- Long-polls `GET /api/daemon/jobs/next` (backend, authenticated via the API key issued at
  registration) for queued print jobs.
- Reads printer state and the loaded media over **IPP** (port 631, `internal/ipp`).
- Decodes the label PNG, converts it to the Brother QL raster command protocol
  (`internal/qlraster`), and sends it to the printer over a raw TCP connection on port 9100.
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
back to the web app) and to declare the correct media in the job. Media is **detected, never
configured** — a job must declare the media kind (continuous tape vs die-cut labels), width, and
for die-cut its fixed length; declaring anything else is rejected as a media error. Detected media
is reported to the app on every poll, so changing the label roll needs no user action.

Check what a printer reports at any time:

```
clele-print-daemon status --printer-ip 192.168.1.56
```

The raster encoding (`internal/qlraster/raster.go`) follows the publicly documented Brother QL
protocol. Print-head margins are computed by centering the media within the 720-dot head; if
labels come out off-center, adjust `toRasterLines`.

## Useful commands

```
systemctl status clele-print-daemon
journalctl -u clele-print-daemon -f
```
