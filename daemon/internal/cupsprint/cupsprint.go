// Package cupsprint drives a USB-attached label printer through the local CUPS queue, over IPP
// only: Get-Printer-Attributes for status and geometry, CUPS-Get-Printers to discover the queues,
// and Print-Job to print.
//
// Developed against a Dymo LabelWriter 320, which has no network interface at all, so the Brother
// approach (address the printer directly) does not apply. Going through CUPS means the packaged
// vendor driver does the device-specific work and the daemon needs no reverse-engineered raster
// protocol, no udev rule, and no group membership -- submitting to localhost:631 works as the
// unprivileged clele-print user.
//
// WHY THE RASTER IS BUILT HERE rather than handing CUPS a PNG: the stock imagetoraster filter
// rescales the image under every scaling option there is (print-scaling=none, ppi=300, scaling=100,
// fitplot=false all resampled a 193x489 px input to 191x477). Label barcodes require an integer
// number of device dots per Code 128 module to survive the 1-bit threshold, so any resampling
// corrupts them. Submitting application/vnd.cups-raster makes CUPS run only the vendor driver.
package cupsprint

import (
	"bytes"
	"fmt"
	"image/png"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/clele/print-daemon/internal/ipp"
	"github.com/clele/print-daemon/internal/printer"
)

// CUPS always listens on the loopback interface of the machine the daemon runs on.
const (
	serverURI = "http://localhost:631/"
	queueBase = "http://localhost:631/printers/"

	// jobUser is the system account the daemon runs as; it shows up as the job owner in CUPS.
	jobUser = "clele-print"

	rasterFormat = "application/vnd.cups-raster"
)

// mediaAttributes are the geometry attributes needed on top of the standard status set.
var mediaAttributes = append(append([]string{}, ipp.StatusAttributes...),
	"media-supported", "media-col-database")

// Driver prints to one CUPS queue.
type Driver struct {
	Queue string
	// MediaKeyword is the label stock the user picked in the web app. A LabelWriter cannot sense
	// which roll is loaded, so unlike the Brother this is configuration, not detection.
	MediaKeyword string
}

func New(queue, mediaKeyword string) *Driver {
	return &Driver{Queue: queue, MediaKeyword: mediaKeyword}
}

func (d *Driver) uri() string { return queueBase + d.Queue }

// Probe reads the queue's state and resolves the geometry of the selected label stock.
func (d *Driver) Probe() (*ipp.PrinterStatus, *printer.Report, error) {
	if d.Queue == "" {
		return nil, nil, fmt.Errorf("no CUPS queue configured")
	}
	attrs, err := ipp.GetPrinterAttributes(d.uri(), mediaAttributes)
	if err != nil {
		return nil, nil, err
	}
	status := ipp.StatusFrom(attrs)

	if m, ok := selectMedia(attrs, d.MediaKeyword); ok {
		status.Media = &m
	}
	report := &printer.Report{Model: status.MakeAndModel, Media: status.Media}
	if status.Media != nil {
		report.PrintableWidthMm = status.Media.PrintableWidthMm()
		report.PrintableLengthMm = status.Media.PrintableLengthMm()
	}
	return status, report, nil
}

// Capabilities lists the machine's CUPS queues and the label stock each one offers, so the web app
// can present both as pickers instead of asking the user to type a queue name.
func (d *Driver) Capabilities() (*printer.Capabilities, error) {
	queues, err := ipp.CUPSGetPrinters(serverURI)
	if err != nil {
		return nil, err
	}
	caps := &printer.Capabilities{Queues: make([]printer.Queue, 0, len(queues))}
	for _, q := range queues {
		entry := printer.Queue{Name: q.Name, Description: q.Info, MakeAndModel: q.MakeAndModel}
		// One extra round trip per queue, but it is a local socket and there are rarely more than
		// a handful, so the whole picker is populated by a single capabilities report.
		if attrs, err := ipp.GetPrinterAttributes(queueBase+q.Name, mediaAttributes); err == nil {
			entry.Media = mediaOptions(attrs)
		}
		caps.Queues = append(caps.Queues, entry)
	}
	sort.Slice(caps.Queues, func(i, j int) bool { return caps.Queues[i].Name < caps.Queues[j].Name })
	return caps, nil
}

// Print rasterises the label and submits it. The printer's state is checked first so a failure
// comes back as the printer's own reason rather than a job that silently disappears.
func (d *Driver) Print(pngBytes []byte) error {
	status, _, err := d.Probe()
	if err != nil {
		return fmt.Errorf("could not read printer status: %w", err)
	}
	if problem := status.Problem(); problem != "" {
		return fmt.Errorf("printer not ready: %s", problem)
	}
	if status.Media == nil {
		return fmt.Errorf("unknown label size %q on queue %s", d.MediaKeyword, d.Queue)
	}

	img, err := png.Decode(bytes.NewReader(pngBytes))
	if err != nil {
		return fmt.Errorf("decode png: %w", err)
	}
	m := *status.Media
	raster := buildRaster(img, page{
		widthMm:          m.WidthMm,
		lengthMm:         m.LengthMm,
		leftMm:           m.LeftMarginMm,
		rightMm:          m.RightMarginMm,
		topMm:            m.TopMarginMm,
		bottomMm:         m.BottomMarginMm,
		printableWidthMm: m.PrintableWidthMm(),
		printableLenMm:   m.PrintableLengthMm(),
		pageSizeName:     pageSizeName(m.Name),
	})
	return ipp.PrintJob(d.uri(), jobUser, "sortiment-label", rasterFormat, m.Name, raster)
}

// ---------------------------------------------------------------- media resolution

// pwgDimsRe matches the self-describing dimensions PWG media names end with, e.g. the "0.75x2in"
// in "custom_0.75x2in_0.75x2in" or the "2x3.5in" in "oe_business-card_2x3.5in".
var pwgDimsRe = regexp.MustCompile(`^([0-9.]+)x([0-9.]+)(in|mm)$`)

// pwgDims returns a media keyword's dimensions in millimetres.
func pwgDims(keyword string) (w, l float64, ok bool) {
	parts := strings.Split(keyword, "_")
	if len(parts) < 2 {
		return 0, 0, false
	}
	m := pwgDimsRe.FindStringSubmatch(parts[len(parts)-1])
	if m == nil {
		return 0, 0, false
	}
	w, _ = strconv.ParseFloat(m[1], 64)
	l, _ = strconv.ParseFloat(m[2], 64)
	if m[3] == "in" {
		w, l = w*25.4, l*25.4
	}
	return w, l, w > 0 && l > 0
}

// pageSizeName recovers the PPD PageSize name the vendor driver expects in the raster header's
// cupsPageSizeName field, from the PWG keyword CUPS reports: "custom_0.75x2in_0.75x2in" ->
// "0.75x2". Leaving that field empty makes raster2dymolw stall part-way through the job and never
// feed the label out to the tear bar.
func pageSizeName(keyword string) string {
	parts := strings.Split(keyword, "_")
	if len(parts) < 2 {
		return keyword
	}
	n := parts[len(parts)-1]
	n = strings.TrimSuffix(n, "in")
	n = strings.TrimSuffix(n, "mm")
	return n
}

// isRangeKeyword filters out the two synthetic entries CUPS appends to describe the custom size
// range it accepts; they are not stock anyone can load.
func isRangeKeyword(k string) bool {
	return strings.HasPrefix(k, "custom_min_") || strings.HasPrefix(k, "custom_max_")
}

// mediaByDims indexes media-col-database by physical size, which is how a media keyword is matched
// to its margins. Matching on size rather than list position is deliberate: media-supported and
// media-col-database are not the same length (CUPS appends custom_min/custom_max to the former),
// so a positional zip would silently shift every entry.
func mediaByDims(attrs ipp.Attrs) map[string]*ipp.Value {
	out := map[string]*ipp.Value{}
	for _, col := range attrs["media-col-database"] {
		size := col.Member("media-size")
		key := fmt.Sprintf("%d/%d", size.Member("x-dimension").Int(), size.Member("y-dimension").Int())
		out[key] = col
	}
	return out
}

func dimsKey(widthMm, lengthMm float64) string {
	return fmt.Sprintf("%d/%d", int(widthMm*100+0.5), int(lengthMm*100+0.5))
}

// selectMedia resolves a media keyword to its full geometry. An empty keyword, or one the queue
// does not offer, falls back to the queue's own default so the daemon still reports something
// sensible while the user has yet to pick.
func selectMedia(attrs ipp.Attrs, keyword string) (ipp.Media, bool) {
	byDims := mediaByDims(attrs)
	if keyword != "" {
		if w, l, ok := pwgDims(keyword); ok {
			if col, found := byDims[dimsKey(w, l)]; found {
				return ipp.MediaFromCol(col, keyword), true
			}
		}
	}
	if col := attrs.First("media-col-default"); col != nil && col.Coll != nil {
		return ipp.MediaFromCol(col, attrs.Str("media-default")), true
	}
	return ipp.Media{}, false
}

// mediaOptions turns the queue's supported media into the list the web app shows in its picker.
func mediaOptions(attrs ipp.Attrs) []printer.MediaOption {
	byDims := mediaByDims(attrs)
	var out []printer.MediaOption
	for _, v := range attrs["media-supported"] {
		if isRangeKeyword(v.S) {
			continue
		}
		w, l, ok := pwgDims(v.S)
		if !ok {
			continue
		}
		col, found := byDims[dimsKey(w, l)]
		if !found {
			continue
		}
		m := ipp.MediaFromCol(col, v.S)
		out = append(out, printer.MediaOption{
			Keyword: v.S,
			// CUPS does not expose the vendor's friendly label name (30252 Address and such) over
			// IPP, only the size, so the size is what identifies a roll in the picker.
			DisplayName:       fmt.Sprintf("%s x %s mm", ipp.Mm(round2(m.WidthMm)), ipp.Mm(round2(m.LengthMm))),
			WidthMm:           round2(m.WidthMm),
			LengthMm:          round2(m.LengthMm),
			PrintableWidthMm:  round2(m.PrintableWidthMm()),
			PrintableLengthMm: round2(m.PrintableLengthMm()),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].WidthMm != out[j].WidthMm {
			return out[i].WidthMm < out[j].WidthMm
		}
		return out[i].LengthMm < out[j].LengthMm
	})
	return out
}

func round2(v float64) float64 { return float64(int(v*100+0.5)) / 100 }
