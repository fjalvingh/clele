package cupsprint

import (
	"encoding/binary"
	"image"
	"image/color"
	"math"
)

// CUPS raster geometry. The page header is a fixed 1796-byte struct (cups_page_header2_t)
// preceded by a 4-byte sync word; v3 raster is UNCOMPRESSED, so the page is simply
// bytesPerLine*cupsHeight raw bytes after it. Verified against filter output: the file size is
// exactly 1800 + bytesPerLine*cupsHeight.
const (
	rasterHeaderLen = 1796
	dotsPerMm       = 300.0 / 25.4
	dpi             = 300
)

// syncV3 is the CUPS raster v3 magic in host (little-endian) byte order.
var syncV3 = []byte{'3', 'S', 'a', 'R'}

// Byte offsets into cups_page_header2_t of the fields the Dymo filter chain reads. Every one of
// these must be set: a header with only the obvious fields filled in prints, but then leaves
// raster2dymolw waiting forever -- CUPS sits at "Printing page 1, 99% complete", the job never
// completes, and the label is never fed far enough to tear off. cupsPageSizeName being empty is
// the likeliest single cause; cupsNumColors, cupsCompression and the float geometry were wrong too.
const (
	offHWResolution                = 276
	offImagingBoundingBox          = 284
	offNumCopies                   = 340
	offPageSize                    = 352
	offCupsWidth                   = 372
	offCupsBitsPerColor            = 384
	offCupsCompression             = 404
	offCupsBorderlessScalingFactor = 424
	offCupsPageSize                = 428
	offCupsImagingBBox             = 436
	offCupsPageSizeName            = 1732
)

// CUPS_CSPACE_RGB, chunked order, 8 bits per colour -- what the stock filter chain hands
// raster2dymolw, so it is what this writes rather than a smaller greyscale format that the driver
// may or may not accept.
const (
	bitsPerColor  = 8
	bitsPerPixel  = 24
	colorOrder    = 0 // chunked
	colorSpaceRGB = 1
	numColors     = 3
)

// page describes where the raster sits on the label, in millimetres.
type page struct {
	widthMm, lengthMm                float64 // physical media, across-head x along-feed
	leftMm, rightMm, topMm, bottomMm float64 // margins the printer cannot mark
	printableWidthMm, printableLenMm float64
	pageSizeName                     string
}

// buildRaster renders a label image as a single-page CUPS raster document.
//
// AXIS MAPPING. The label PNG uses the same convention as the Brother path
// (qlraster.toRasterLines): its x axis is the FEED direction and its y axis runs across the print
// head. CUPS raster is indexed the other way round -- a row is one line along the feed, a column is
// a dot across the head. A plain transpose looks correct on paper but prints MIRRORED, because the
// Dymo's column 0 is the opposite end of the head from the Brother's dot 0. The mapping that
// matches the hardware is a transpose plus a flip of the head axis:
//
//	dst(col, row) = src(x = row, y = srcHeight-1-col)
//
// Established by printing an asymmetric test label (a solid bar along one long edge, an "R" at one
// end) and looking at which corner they came out in. Keeping the correction here means one PNG
// serves both drivers and the frontend stays printer-agnostic.
func buildRaster(img image.Image, p page) []byte {
	b := img.Bounds()
	srcW, srcH := b.Dx(), b.Dy()
	cupsWidth, cupsHeight := srcH, srcW
	bytesPerLine := cupsWidth * 3

	hdr := make([]byte, rasterHeaderLen)
	putU := func(off int, vals ...uint32) {
		for i, v := range vals {
			binary.LittleEndian.PutUint32(hdr[off+4*i:], v)
		}
	}
	putF := func(off int, vals ...float64) {
		for i, v := range vals {
			binary.LittleEndian.PutUint32(hdr[off+4*i:], math.Float32bits(float32(v)))
		}
	}

	// PostScript convention: the bounding box is [left, bottom, right, top] measured from the
	// bottom-left of the page, so the TOP margin is subtracted from the page length.
	pt := func(mm float64) float64 { return mm / 25.4 * 72.0 }
	bbL, bbB := pt(p.leftMm), pt(p.bottomMm)
	bbR, bbT := pt(p.widthMm-p.rightMm), pt(p.lengthMm-p.topMm)
	pgW, pgH := pt(p.widthMm), pt(p.lengthMm)

	putU(offHWResolution, dpi, dpi)
	putU(offImagingBoundingBox, uint32(bbL), uint32(bbB), uint32(bbR), uint32(bbT))
	putU(offNumCopies, 1)
	putU(offPageSize, uint32(pgW), uint32(pgH))
	putU(offCupsWidth, uint32(cupsWidth), uint32(cupsHeight))
	putU(offCupsBitsPerColor, bitsPerColor, bitsPerPixel, uint32(bytesPerLine), colorOrder, colorSpaceRGB)
	putU(offCupsCompression, 0, 0, 0, 0, numColors) // Compression, RowCount, RowFeed, RowStep, NumColors
	putF(offCupsBorderlessScalingFactor, 1.0)
	putF(offCupsPageSize, pgW, pgH)
	putF(offCupsImagingBBox, bbL, bbB, bbR, bbT)
	copy(hdr[offCupsPageSizeName:offCupsPageSizeName+64], p.pageSizeName)

	out := make([]byte, 0, len(syncV3)+rasterHeaderLen+bytesPerLine*cupsHeight)
	out = append(out, syncV3...)
	out = append(out, hdr...)

	row := make([]byte, bytesPerLine)
	for r := 0; r < cupsHeight; r++ {
		for c := 0; c < cupsWidth; c++ {
			v := byte(0xFF)
			if isDark(img.At(b.Min.X+r, b.Min.Y+srcH-1-c)) {
				v = 0x00
			}
			row[c*3], row[c*3+1], row[c*3+2] = v, v, v
		}
		out = append(out, row...)
	}
	return out
}

// isDark thresholds to 1 bit with the same rule as the Brother path: no dithering, so a barcode's
// bars stay exactly on the device dot grid and an integer module width survives to the paper.
func isDark(c color.Color) bool {
	return color.GrayModel.Convert(c).(color.Gray).Y < 128
}
