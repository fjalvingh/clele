// Package qlraster converts a label image into the Brother QL raster command stream and sends it
// to a network-connected QL-series printer over its raw TCP print port (9100).
//
// Command structure follows the publicly documented Brother QL raster protocol (as also
// implemented by the well-known brother_ql open-source project): invalidate, initialize, switch
// to raster mode, print-information, one raster-transfer command per line, then print+eject.
//
// MEDIA: the QL-700/710W/720NW family has a 720-dot (300 dpi, ~62mm) print head. A job must
// declare the media actually loaded — its kind (continuous tape vs die-cut labels), its width,
// and for die-cut its fixed length. Declaring the wrong media makes the printer abort the job
// (flashing red LED / "ERROR"). The media is not guessed: it is read from the printer over IPP
// (see internal/ipp), which reports it exactly.
package qlraster

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"net"
	"time"

	"github.com/clele/print-daemon/internal/ipp"
)

const printHeadDots = 720
const printHeadBytes = printHeadDots / 8
const dotsPerMm = 300.0 / 25.4

// Print geometry, measured on a QL-710W with 17x54mm die-cut labels rather than derived — these
// are physical properties of the printer that cannot be computed from the media size:
//
//   - Media is LEFT-ALIGNED on the print head: the label starts at dot 0, not centred. Verified by
//     printing bands from different head zones and observing which landed on the label. Centring
//     the content (the previous behaviour) placed it at dots 280-440, entirely off a 17mm label,
//     which is why labels came out blank.
//   - The printer feeds ~6mm before printing starts, so a die-cut label's printable length is its
//     physical length minus that lead. Sending the full length overruns; sending too few lines
//     makes the printer cut the label short.
//   - The far edge across the head has ~2mm the head cannot reach.
const (
	mediaOffsetDots   = 0   // left-aligned
	dieCutLeadMm      = 6.0 // fed before printing begins
	unprintableEdgeMm = 2.0 // across the head, at the far edge
)

// BuildCommands decodes a PNG and returns the full Brother QL raster command stream for the given
// media, which must match what is physically loaded in the printer.
func BuildCommands(pngBytes []byte, media ipp.Media) ([]byte, error) {
	img, err := png.Decode(bytes.NewReader(pngBytes))
	if err != nil {
		return nil, fmt.Errorf("decode png: %w", err)
	}
	if media.WidthMm <= 0 || media.WidthMm > 62 {
		return nil, fmt.Errorf("invalid media width %smm (must be 1-62)", ipp.Mm(media.WidthMm))
	}
	if media.DieCut && media.LengthMm <= 0 {
		return nil, fmt.Errorf("die-cut media requires a label length")
	}

	lines := toRasterLines(img)

	// Die-cut labels have a fixed printable length: the printer cuts at the die boundary, so it
	// must receive exactly that many raster lines. Too few and it cuts the label short (a 400-line
	// job on a 54mm label cut at 44mm); too many overruns into the next label.
	if media.DieCut {
		lines = fitLines(lines, printableLines(media.LengthMm))
	}

	var buf bytes.Buffer
	buf.Write(initSequence())                 // invalidate + initialize
	buf.Write([]byte{0x1B, 0x69, 0x61, 0x01}) // ESC i a — switch to raster command mode

	// ESC i z — print information: declare the media so the printer accepts the job. PI_LENGTH is
	// only valid for die-cut labels, which have a fixed length; continuous tape does not, and
	// declaring a length for it is itself a mismatch.
	const (
		piKind    = 0x02
		piWidth   = 0x04
		piLength  = 0x08
		piRecover = 0x80
	)
	piFlags := byte(piKind | piWidth | piRecover)
	mediaType := byte(MediaContinuous)
	lengthMm := byte(0)
	if media.DieCut {
		piFlags |= piLength
		mediaType = MediaDieCut
		lengthMm = byte(media.LengthMm)
	}

	rasterCount := uint32(len(lines))
	buf.Write([]byte{0x1B, 0x69, 0x7A, piFlags, mediaType, byte(media.WidthMm), lengthMm,
		byte(rasterCount), byte(rasterCount >> 8), byte(rasterCount >> 16), byte(rasterCount >> 24),
		0x00, 0x00})

	// ESC i M — various mode settings. Bit 6 (0x40) enables auto-cut. This is NOT the compression
	// command (a previous version of this code conflated the two, which both disabled the cutter
	// and left the compression mode unset).
	buf.Write([]byte{0x1B, 0x69, 0x4D, 0x40})

	// ESC i K — expanded mode settings. Bit 3 (0x08) cuts at the end of the job.
	buf.Write([]byte{0x1B, 0x69, 0x4B, 0x08})

	// M — select compression mode. 0x00 = no compression, matching the raw raster lines below.
	// Without this the printer decodes the raster stream using whatever mode it was left in.
	buf.Write([]byte{0x4D, 0x00})

	for _, line := range lines {
		buf.WriteByte(0x67) // g — raster line transfer
		buf.WriteByte(0x00)
		buf.WriteByte(byte(len(line)))
		buf.Write(line)
	}

	buf.WriteByte(0x1A) // print, feed and cut (final page)

	return buf.Bytes(), nil
}

// printableLines is how many raster lines fit on a die-cut label of the given physical length,
// allowing for the lead the printer feeds before it starts printing.
func printableLines(lengthMm float64) int {
	n := int((lengthMm - dieCutLeadMm) * dotsPerMm)
	if n < 1 {
		n = 1
	}
	return n
}

// fitLines pads with blank lines or truncates so exactly want lines are emitted.
func fitLines(lines [][]byte, want int) [][]byte {
	if len(lines) > want {
		return lines[:want]
	}
	for len(lines) < want {
		lines = append(lines, make([]byte, printHeadBytes))
	}
	return lines
}

// toRasterLines turns the image into 1-bit-per-pixel raster lines, each printHeadBytes wide.
// The image's x axis is the feed direction (one raster line per column) and its y axis spans the
// print head. The image is placed LEFT-ALIGNED at mediaOffsetDots — see the geometry constants
// above; centring it misses narrow labels entirely.
func toRasterLines(img image.Image) [][]byte {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()

	lines := make([][]byte, w)
	for x := 0; x < w; x++ {
		line := make([]byte, printHeadBytes)
		for y := 0; y < h; y++ {
			if isDark(img.At(b.Min.X+x, b.Min.Y+y)) {
				bitPos := mediaOffsetDots + y
				if bitPos >= 0 && bitPos < printHeadDots {
					line[bitPos/8] |= 0x80 >> (bitPos % 8)
				}
			}
		}
		lines[x] = line
	}
	return lines
}

func isDark(c color.Color) bool {
	gray := color.GrayModel.Convert(c).(color.Gray)
	return gray.Y < 128
}

// initSequence resets the printer to a known state: 200 invalidate bytes then ESC @ (initialize).
func initSequence() []byte {
	return append(bytes.Repeat([]byte{0x00}, 200), 0x1B, 0x40)
}

// Print sends a label to the printer, using IPP to check state and detect media first.
//
// Status comes from IPP (port 631) rather than the raster protocol's ESC i S: the QL-710W's raw
// port 9100 is write-only and never answers a status request, so a job written there succeeds
// silently even when the printer is flashing an error. IPP reports both the fault and the media
// actually loaded, which is what the print-information command must declare.
//
// The detected media is authoritative — it cannot go stale the way a manual setting can.
func Print(printerIP string, pngBytes []byte) (*ipp.Media, error) {
	status, err := ipp.GetPrinterStatus(printerIP)
	if err != nil {
		return nil, fmt.Errorf("could not read printer status: %w", err)
	}
	if problem := status.Problem(); problem != "" {
		return status.Media, fmt.Errorf("printer reports: %s", problem)
	}
	if status.Media == nil {
		return nil, fmt.Errorf("printer did not report what media is loaded")
	}

	commands, err := BuildCommands(pngBytes, *status.Media)
	if err != nil {
		return status.Media, err
	}

	conn, err := net.DialTimeout("tcp", printerIP+":9100", 10*time.Second)
	if err != nil {
		return status.Media, fmt.Errorf("connect to printer: %w", err)
	}
	defer conn.Close()

	if err := conn.SetWriteDeadline(time.Now().Add(30 * time.Second)); err != nil {
		return status.Media, err
	}
	if _, err := io.Copy(conn, bytes.NewReader(commands)); err != nil {
		return status.Media, fmt.Errorf("write to printer: %w", err)
	}

	// Port 9100 gives no completion signal, so confirm via IPP instead: after a short settle,
	// re-read state and surface any fault the job just caused.
	time.Sleep(1500 * time.Millisecond)
	if after, err := ipp.GetPrinterStatus(printerIP); err == nil {
		if problem := after.Problem(); problem != "" {
			return after.Media, fmt.Errorf("printer reports after printing: %s", problem)
		}
	}
	return status.Media, nil
}
