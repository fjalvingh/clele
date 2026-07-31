// Package qlraster converts a label image into the Brother QL raster command stream and sends it
// to a network-connected QL-series printer over its raw TCP print port (9100).
//
// Command structure follows the publicly documented Brother QL raster protocol (as also
// implemented by the well-known brother_ql open-source project): invalidate, initialize, switch
// to raster mode, print-information, one raster-transfer command per line, then print+eject.
//
// PRINT-HEAD WIDTH: the QL-700/710W/720NW family has a 720-dot (300 dpi, ~61mm) print head, but
// the printable width for a given job is the width of the continuous tape actually loaded
// (12/29/38/50/54/62mm). The print-information command below declares that width — this must
// match the physical tape or the printer aborts (flashing red LED / "ERROR"). tapeWidthMm is a
// per-daemon setting (Settings page), not guessed, since it isn't discoverable from software.
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
)

const printHeadDots = 720
const printHeadBytes = printHeadDots / 8
const dotsPerMm = 300.0 / 25.4

// BuildCommands decodes a PNG and returns the full Brother QL raster command stream, for a
// continuous tape of the given width (mm) — must match what's physically loaded in the printer.
func BuildCommands(pngBytes []byte, tapeWidthMm int) ([]byte, error) {
	img, err := png.Decode(bytes.NewReader(pngBytes))
	if err != nil {
		return nil, fmt.Errorf("decode png: %w", err)
	}
	if tapeWidthMm <= 0 || tapeWidthMm > 62 {
		return nil, fmt.Errorf("invalid tape width %dmm (must be 1-62)", tapeWidthMm)
	}

	tapeWidthDots := int(float64(tapeWidthMm) * dotsPerMm)
	lines := toRasterLines(img, tapeWidthDots)

	var buf bytes.Buffer
	buf.Write(bytes.Repeat([]byte{0x00}, 200)) // invalidate
	buf.Write([]byte{0x1B, 0x40})              // initialize
	buf.Write([]byte{0x1B, 0x69, 0x61, 0x01})  // switch to raster command mode

	// Print information command (ESC i z): declare continuous media of the configured width so
	// the printer accepts the job (an undeclared/mismatched width is rejected as a media error;
	// leaving PI_LENGTH unset is standard for continuous tape, whose length isn't fixed).
	const (
		piKind    = 0x02
		piWidth   = 0x04
		piRecover = 0x80
	)
	piFlags := byte(piKind | piWidth | piRecover)
	mediaTypeContinuous := byte(0x0A)
	rasterCount := uint32(len(lines))
	buf.Write([]byte{0x1B, 0x69, 0x7A, piFlags, mediaTypeContinuous, byte(tapeWidthMm), 0x00,
		byte(rasterCount), byte(rasterCount >> 8), byte(rasterCount >> 16), byte(rasterCount >> 24),
		0x00, 0x00})

	buf.Write([]byte{0x1B, 0x69, 0x4D, 0x00}) // select compression mode: none (raster lines below are uncompressed)
	buf.Write([]byte{0x1B, 0x69, 0x4B, 0x08}) // various mode settings: auto-cut enabled

	for _, line := range lines {
		buf.WriteByte(0x67)
		buf.WriteByte(0x00)
		buf.WriteByte(byte(len(line)))
		buf.Write(line)
	}

	buf.WriteByte(0x1A) // print, feed and cut (final page)

	return buf.Bytes(), nil
}

// toRasterLines rotates the image 90° (so its long edge feeds through the printer) and
// thresholds it to 1-bit-per-pixel raster lines, each printHeadBytes long, with the image
// centered within the tape's actual printable width (not the full 720-dot head).
func toRasterLines(img image.Image, tapeWidthDots int) [][]byte {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()

	headOffset := (printHeadDots - tapeWidthDots) / 2
	contentOffset := (tapeWidthDots - h) / 2
	if contentOffset < 0 {
		contentOffset = 0
	}
	offsetBits := headOffset + contentOffset

	lines := make([][]byte, w)
	for x := 0; x < w; x++ {
		line := make([]byte, printHeadBytes)
		for y := 0; y < h; y++ {
			if isDark(img.At(b.Min.X+x, b.Min.Y+y)) {
				bitPos := offsetBits + y
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

// SendToPrinter opens a raw TCP connection to the printer's port 9100 (standard raw/JetDirect
// print port, which Brother QL network models also expose for raster command streams) and writes
// the command stream.
func SendToPrinter(printerIP string, commands []byte) error {
	conn, err := net.DialTimeout("tcp", printerIP+":9100", 10*time.Second)
	if err != nil {
		return fmt.Errorf("connect to printer: %w", err)
	}
	defer conn.Close()

	conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
	if _, err := io.Copy(conn, bytes.NewReader(commands)); err != nil {
		return fmt.Errorf("write to printer: %w", err)
	}
	return nil
}
