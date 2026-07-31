package qlraster

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"testing"
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

func TestBuildCommandsProducesNonEmptyRasterData(t *testing.T) {
	pngBytes := buildTestPng(t)
	cmds, err := BuildCommands(pngBytes, 62)
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
