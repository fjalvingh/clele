package qlraster

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"testing"

	"github.com/clele/print-daemon/internal/ipp"
)

// buildTestPng renders a small black rectangle on white, like a real label with text on it.
func buildTestPng(t *testing.T) []byte {
	t.Helper()
	w, h := 100, 40
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.White)
		}
	}
	for y := 10; y < 25; y++ {
		for x := 5; x < 60; x++ {
			img.Set(x, y, color.Black)
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatalf("encode png: %v", err)
	}
	return buf.Bytes()
}

// Locks in the command sequence against the Brother QL spec. A previous version conflated
// ESC i M (various mode settings) with the compression-mode command, which silently disabled the
// cutter and left compression unset.
func TestBuildCommandsEmitsCorrectControlCommands(t *testing.T) {
	cmds, err := BuildCommands(buildTestPng(t), ipp.Media{WidthMm: 62})
	if err != nil {
		t.Fatalf("BuildCommands: %v", err)
	}

	for _, tc := range []struct {
		name string
		want []byte
	}{
		{"raster mode (ESC i a 1)", []byte{0x1B, 0x69, 0x61, 0x01}},
		{"auto-cut on (ESC i M 0x40)", []byte{0x1B, 0x69, 0x4D, 0x40}},
		{"cut at end (ESC i K 0x08)", []byte{0x1B, 0x69, 0x4B, 0x08}},
		{"compression none (M 0x00)", []byte{0x4D, 0x00}},
	} {
		if !bytes.Contains(cmds, tc.want) {
			t.Errorf("missing %s: % X", tc.name, tc.want)
		}
	}

	// Auto-cut must not be left disabled.
	if bytes.Contains(cmds, []byte{0x1B, 0x69, 0x4D, 0x00}) {
		t.Error("ESC i M 0x00 present — auto-cut is disabled")
	}
	if cmds[len(cmds)-1] != 0x1A {
		t.Errorf("stream must end with print+cut (0x1A), got 0x%02X", cmds[len(cmds)-1])
	}
}

// Die-cut labels must be declared with the die-cut media type AND a valid length; continuous tape
// must not carry a length. Getting this wrong is what the printer rejects as a media mismatch.
func TestBuildCommandsDeclaresMediaCorrectly(t *testing.T) {
	png := buildTestPng(t)

	dieCut, err := BuildCommands(png, ipp.Media{WidthMm: 17, LengthMm: 54, DieCut: true})
	if err != nil {
		t.Fatalf("die-cut BuildCommands: %v", err)
	}
	pi := indexOf(t, dieCut, []byte{0x1B, 0x69, 0x7A})
	if got := dieCut[pi+3]; got&0x08 == 0 {
		t.Errorf("die-cut must set PI_LENGTH, flags = 0x%02X", got)
	}
	if got := dieCut[pi+4]; got != MediaDieCut {
		t.Errorf("expected die-cut media type 0x%02X, got 0x%02X", MediaDieCut, got)
	}
	if dieCut[pi+5] != 17 || dieCut[pi+6] != 54 {
		t.Errorf("expected 17x54mm, got %dx%dmm", dieCut[pi+5], dieCut[pi+6])
	}

	cont, err := BuildCommands(png, ipp.Media{WidthMm: 62})
	if err != nil {
		t.Fatalf("continuous BuildCommands: %v", err)
	}
	pi = indexOf(t, cont, []byte{0x1B, 0x69, 0x7A})
	if got := cont[pi+3]; got&0x08 != 0 {
		t.Errorf("continuous tape must not set PI_LENGTH, flags = 0x%02X", got)
	}
	if got := cont[pi+4]; got != MediaContinuous {
		t.Errorf("expected continuous media type 0x%02X, got 0x%02X", MediaContinuous, got)
	}
	if cont[pi+6] != 0 {
		t.Errorf("continuous tape must declare length 0, got %d", cont[pi+6])
	}
}

func TestBuildCommandsRejectsDieCutWithoutLength(t *testing.T) {
	if _, err := BuildCommands(buildTestPng(t), ipp.Media{WidthMm: 17, DieCut: true}); err == nil {
		t.Error("expected an error for die-cut media with no length")
	}
}

func indexOf(t *testing.T, haystack, needle []byte) int {
	t.Helper()
	i := bytes.Index(haystack, needle)
	if i < 0 {
		t.Fatalf("sequence % X not found", needle)
	}
	return i
}

func TestBuildCommandsProducesNonEmptyRasterData(t *testing.T) {
	pngBytes := buildTestPng(t)
	cmds, err := BuildCommands(pngBytes, ipp.Media{WidthMm: 62})
	if err != nil {
		t.Fatalf("BuildCommands: %v", err)
	}

	// Count set bits across all raster-line payload bytes (each line: 0x67 0x00 <len> <data...>).
	setBits := 0
	i := 0
	lines := 0
	for i < len(cmds) {
		if cmds[i] == 0x67 && i+2 < len(cmds) {
			n := int(cmds[i+2])
			data := cmds[i+3 : i+3+n]
			for _, b := range data {
				for bit := 0; bit < 8; bit++ {
					if b&(0x80>>bit) != 0 {
						setBits++
					}
				}
			}
			lines++
			i += 3 + n
		} else {
			i++
		}
	}

	t.Logf("raster lines: %d, set bits: %d, total command bytes: %d", lines, setBits, len(cmds))
	if lines == 0 {
		t.Fatal("no raster lines found in command stream")
	}
	if setBits == 0 {
		t.Fatal("raster data has zero set bits — the rendered image never reaches the printer as visible dots")
	}
}
