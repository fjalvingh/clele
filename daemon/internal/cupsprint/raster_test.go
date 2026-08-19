package cupsprint

import (
	"encoding/binary"
	"image"
	"image/color"
	"math"
	"testing"
)

// testPage is the 19.05 x 50.8 mm Dymo stock the geometry was verified against, with the margins
// CUPS reports for it.
func testPage() page {
	return page{
		widthMm: 19.05, lengthMm: 50.80,
		leftMm: 1.44, rightMm: 1.02, topMm: 5.76, bottomMm: 1.52,
		printableWidthMm: 16.59, printableLenMm: 43.52,
		pageSizeName: "0.75x2",
	}
}

func u32(t *testing.T, raster []byte, off int) uint32 {
	t.Helper()
	return binary.LittleEndian.Uint32(raster[len(syncV3)+off:])
}

// pixel reads one raster dot: true when black.
func pixel(raster []byte, bytesPerLine, col, row int) bool {
	base := len(syncV3) + rasterHeaderLen + row*bytesPerLine + col*3
	return raster[base] < 128
}

func TestRasterIsUncompressedAndExactlySized(t *testing.T) {
	img := image.NewGray(image.Rect(0, 0, 514, 195))
	raster := buildRaster(img, testPage())

	cupsWidth, cupsHeight := 195, 514 // transposed: width is across the head
	want := len(syncV3) + rasterHeaderLen + cupsWidth*3*cupsHeight
	if len(raster) != want {
		t.Errorf("raster is %d bytes, want %d — v3 raster is uncompressed, so the page is exactly "+
			"bytesPerLine*cupsHeight after the header", len(raster), want)
	}
	if got := u32(t, raster, offCupsWidth); got != uint32(cupsWidth) {
		t.Errorf("cupsWidth = %d, want %d (across the print head)", got, cupsWidth)
	}
	if got := u32(t, raster, offCupsWidth+4); got != uint32(cupsHeight) {
		t.Errorf("cupsHeight = %d, want %d (along the feed)", got, cupsHeight)
	}
}

// TestRasterHeaderFieldsTheDriverNeeds pins the fields whose absence made raster2dymolw stall
// part-way through a job: CUPS reported "Printing page 1, 99% complete" forever, the job never
// completed, and the label was never fed out far enough to tear off. Found by diffing this header
// against one produced by the stock imagetoraster filter.
func TestRasterHeaderFieldsTheDriverNeeds(t *testing.T) {
	raster := buildRaster(image.NewGray(image.Rect(0, 0, 8, 4)), testPage())

	if got := u32(t, raster, offCupsCompression); got != 0 {
		t.Errorf("cupsCompression = %d, want 0", got)
	}
	if got := u32(t, raster, offCupsCompression+16); got != numColors {
		t.Errorf("cupsNumColors = %d, want %d", got, numColors)
	}
	if got := math.Float32frombits(u32(t, raster, offCupsBorderlessScalingFactor)); got != 1.0 {
		t.Errorf("cupsBorderlessScalingFactor = %v, want 1.0", got)
	}
	if got := math.Float32frombits(u32(t, raster, offCupsPageSize)); got == 0 {
		t.Error("cupsPageSize is zero; the driver reads the float page geometry as well as the integer")
	}
	name := raster[len(syncV3)+offCupsPageSizeName : len(syncV3)+offCupsPageSizeName+6]
	if string(name) != "0.75x2" {
		t.Errorf("cupsPageSizeName = %q, want %q — an empty name is what stalls the job", name, "0.75x2")
	}
}

// TestRasterAxisMapping pins the transpose-plus-head-flip established on the hardware. A plain
// transpose builds a valid raster that prints MIRRORED, so this is not something the type system
// or a size check would catch.
func TestRasterAxisMapping(t *testing.T) {
	const srcW, srcH = 4, 3
	img := image.NewGray(image.Rect(0, 0, srcW, srcH))
	for x := 0; x < srcW; x++ {
		for y := 0; y < srcH; y++ {
			img.SetGray(x, y, color.Gray{Y: 0xFF})
		}
	}
	img.SetGray(0, 0, color.Gray{Y: 0x00}) // one black dot at the image origin

	raster := buildRaster(img, testPage())
	bytesPerLine := srcH * 3

	// src(x=0, y=0) must land at row 0 (first line fed) and column srcH-1 (the far end of the head).
	if !pixel(raster, bytesPerLine, srcH-1, 0) {
		t.Error("src(0,0) did not land at (col=srcH-1, row=0); the head axis flip is missing")
	}
	if pixel(raster, bytesPerLine, 0, 0) {
		t.Error("src(0,0) landed at column 0 — that is a plain transpose, which prints mirrored")
	}
}

// TestRasterPreservesModuleGrid is the property the stock imagetoraster filter fails: it resampled
// a 193x489 px input to 191x477, which smears a Code 128 module off the device dot grid and makes
// the barcode unscannable. Building the raster ourselves must be exact.
func TestRasterPreservesModuleGrid(t *testing.T) {
	const moduleDots = 3
	const srcW, srcH = 90, 16
	img := image.NewGray(image.Rect(0, 0, srcW, srcH))
	for x := 0; x < srcW; x++ {
		// Alternating bars exactly moduleDots wide, along the feed direction.
		v := uint8(0xFF)
		if (x/moduleDots)%2 == 0 {
			v = 0x00
		}
		for y := 0; y < srcH; y++ {
			img.SetGray(x, y, color.Gray{Y: v})
		}
	}

	raster := buildRaster(img, testPage())
	bytesPerLine := srcH * 3

	// Walk down the feed axis and confirm every run is exactly one module wide.
	run, prev := 0, pixel(raster, bytesPerLine, 0, 0)
	for row := 0; row <= srcW; row++ {
		var cur bool
		if row < srcW {
			cur = pixel(raster, bytesPerLine, 0, row)
		}
		if row == srcW || cur != prev {
			if run != moduleDots {
				t.Fatalf("run of %d dots at row %d, want exactly %d — the module grid was not preserved",
					run, row, moduleDots)
			}
			run, prev = 0, cur
		}
		run++
	}
}

func TestPwgDimsAndPageSizeName(t *testing.T) {
	cases := []struct {
		keyword string
		w, l    float64
		name    string
	}{
		{"custom_0.75x2in_0.75x2in", 19.05, 50.8, "0.75x2"},
		{"custom_54.02x100.84mm_54.02x100.84mm", 54.02, 100.84, "54.02x100.84"},
		{"oe_business-card_2x3.5in", 50.8, 88.9, "2x3.5"},
	}
	for _, c := range cases {
		w, l, ok := pwgDims(c.keyword)
		if !ok || math.Abs(w-c.w) > 0.01 || math.Abs(l-c.l) > 0.01 {
			t.Errorf("pwgDims(%q) = %v x %v (ok=%v), want %v x %v", c.keyword, w, l, ok, c.w, c.l)
		}
		if got := pageSizeName(c.keyword); got != c.name {
			t.Errorf("pageSizeName(%q) = %q, want %q", c.keyword, got, c.name)
		}
	}
	if !isRangeKeyword("custom_min_3.53x3.53mm") || isRangeKeyword("custom_0.75x2in_0.75x2in") {
		t.Error("custom_min/custom_max must be filtered out of the media picker, real sizes kept")
	}
}
