package ipp

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

// Endpoint paths vary by vendor; these cover Brother and the common defaults. A CUPS queue is
// addressed directly instead (see cupsprint), so this list is only used for network printers.
var candidatePaths = []string{"/ipp/print", "/ipp/port1", "/"}

// StatusAttributes is what a printer must be asked for to fill in a PrinterStatus.
var StatusAttributes = []string{
	"printer-state",
	"printer-state-reasons",
	"printer-state-message",
	"printer-is-accepting-jobs",
	"printer-make-and-model",
	"media-ready",
	"media-default",
	"media-col-default",
}

// printer-state enum values (RFC 8011 5.4.11).
const (
	stateIdle       = 3
	stateProcessing = 4
	stateStopped    = 5
)

// Media is the label stock a job is formatted for.
//
// Dimensions are millimetres and deliberately fractional: Dymo stock is sized in inches, so a
// common roll is 19.05 x 50.8 mm, and rounding it to whole millimetres would put the label edge
// half a millimetre out. Brother stock is whole millimetres and is unaffected.
type Media struct {
	// Name is the raw IPP media keyword, e.g. "om_brother-label-17x54mm_17x54mm" or
	// "custom_0.75x2in_0.75x2in".
	Name string
	// WidthMm is the dimension across the print head (the narrow edge of the label).
	WidthMm float64
	// LengthMm is the dimension along the feed direction. 0 for continuous stock, which has no
	// fixed length.
	LengthMm float64
	// DieCut distinguishes pre-cut labels (fixed length) from continuous tape.
	DieCut bool
	// Margins the printer cannot mark, when it reports them (CUPS does, via media-col; a bare
	// network printer generally does not and leaves these zero).
	LeftMarginMm, RightMarginMm float64
	TopMarginMm, BottomMarginMm float64
}

// PrintableWidthMm is the markable extent across the print head.
func (m Media) PrintableWidthMm() float64 {
	return m.WidthMm - m.LeftMarginMm - m.RightMarginMm
}

// PrintableLengthMm is the markable extent along the feed, 0 for continuous stock.
func (m Media) PrintableLengthMm() float64 {
	if m.LengthMm == 0 {
		return 0
	}
	return m.LengthMm - m.TopMarginMm - m.BottomMarginMm
}

func (m *Media) String() string {
	if m == nil {
		return "unknown media"
	}
	if m.DieCut {
		return fmt.Sprintf("%s x %s mm die-cut labels", Mm(m.WidthMm), Mm(m.LengthMm))
	}
	return fmt.Sprintf("%s mm continuous tape", Mm(m.WidthMm))
}

// Mm renders a millimetre measurement for display and for the report headers: rounded to a
// hundredth (a twelfth of a dot at 300 dpi, so below anything printable) and without trailing
// zeros, so whole sizes stay readable ("62", not "62.00") while fractional Dymo sizes keep their
// precision ("19.05"). Rounding here also keeps binary-float noise out of the wire format --
// 50.8 - 5.76 - 1.52 is otherwise 43.519999999999996.
func Mm(v float64) string {
	return strconv.FormatFloat(math.Round(v*100)/100, 'f', -1, 64)
}

// PrinterStatus is the subset of printer attributes this daemon acts on.
type PrinterStatus struct {
	State         string // "idle", "processing", "stopped" or "unknown"
	StateReasons  []string
	StateMessage  string
	AcceptingJobs bool
	MakeAndModel  string
	Media         *Media
}

// Stopped reports whether the printer is halted and will not accept work.
func (s *PrinterStatus) Stopped() bool { return s.State == "stopped" }

// Problem returns a human-readable fault description, or "" when the printer looks healthy.
// "none" is IPP's explicit "nothing wrong" reason and is not a fault.
func (s *PrinterStatus) Problem() string {
	var reasons []string
	for _, r := range s.StateReasons {
		if r != "" && r != "none" {
			reasons = append(reasons, strings.ReplaceAll(r, "-", " "))
		}
	}
	if !s.Stopped() && len(reasons) == 0 {
		return ""
	}
	msg := strings.Join(reasons, "; ")
	if msg == "" {
		msg = "printer stopped"
	}
	if s.StateMessage != "" {
		msg += " (" + s.StateMessage + ")"
	}
	return msg
}

// GetPrinterStatus queries a network printer by address, trying the usual endpoint paths in turn.
func GetPrinterStatus(printerIP string) (*PrinterStatus, error) {
	var lastErr error
	for _, path := range candidatePaths {
		attrs, err := GetPrinterAttributes("http://"+printerIP+":631"+path, StatusAttributes)
		if err == nil {
			return StatusFrom(attrs), nil
		}
		lastErr = err
	}
	return nil, lastErr
}

// StatusFrom builds a PrinterStatus from an already-fetched attribute set, so a caller that needed
// other attributes in the same round trip does not have to ask twice.
func StatusFrom(a Attrs) *PrinterStatus {
	s := &PrinterStatus{State: "unknown"}
	switch a.Str("printer-state") {
	case strconv.Itoa(stateIdle):
		s.State = "idle"
	case strconv.Itoa(stateProcessing):
		s.State = "processing"
	case strconv.Itoa(stateStopped):
		s.State = "stopped"
	}
	for _, v := range a["printer-state-reasons"] {
		s.StateReasons = append(s.StateReasons, v.S)
	}
	s.StateMessage = a.Str("printer-state-message")
	s.AcceptingJobs = a.Str("printer-is-accepting-jobs") == "true"
	s.MakeAndModel = a.Str("printer-make-and-model")

	// Prefer media-col, which states the size and margins numerically; fall back to parsing the
	// media keyword, which is all a bare network printer offers.
	if col := a.First("media-col-default"); col != nil && col.Coll != nil {
		m := MediaFromCol(col, a.Str("media-default"))
		s.Media = &m
	} else if m := parseMedia(a.Str("media-ready")); m != nil {
		s.Media = m
	}
	return s
}

// MediaFromCol turns an IPP media-col collection into a Media. CUPS reports every dimension in
// hundredths of a millimetre, so this is exact -- no guessing from the media name.
func MediaFromCol(col *Value, keyword string) Media {
	size := col.Member("media-size")
	m := Media{
		Name:           keyword,
		WidthMm:        hundredths(size.Member("x-dimension")),
		LengthMm:       hundredths(size.Member("y-dimension")),
		LeftMarginMm:   hundredths(col.Member("media-left-margin")),
		RightMarginMm:  hundredths(col.Member("media-right-margin")),
		TopMarginMm:    hundredths(col.Member("media-top-margin")),
		BottomMarginMm: hundredths(col.Member("media-bottom-margin")),
	}
	// Roll stock is described with an implausibly long y-dimension (CUPS reports metres); anything
	// of an ordinary label length is pre-cut, which is all this daemon prints on.
	m.DieCut = m.LengthMm > 0 && m.LengthMm < 1000
	if !m.DieCut {
		m.LengthMm = 0
	}
	return m
}

func hundredths(v *Value) float64 {
	if v == nil {
		return 0
	}
	return float64(v.Int()) / 100.0
}

// mediaDimsRe matches the trailing dimensions of an IPP media keyword, e.g. the "17x54mm" in
// "om_brother-label-17x54mm_17x54mm".
var mediaDimsRe = regexp.MustCompile(`(\d+)x(\d+)mm`)

// parseMedia turns an IPP media keyword into concrete dimensions, for a printer that reports no
// media-col. Brother names die-cut stock with both dimensions ("...-label-17x54mm...") and
// continuous stock with only a width.
func parseMedia(name string) *Media {
	if name == "" {
		return nil
	}
	m := &Media{Name: name}
	lower := strings.ToLower(name)
	continuous := strings.Contains(lower, "continuous") || strings.Contains(lower, "roll")

	if dims := mediaDimsRe.FindStringSubmatch(lower); dims != nil {
		w, _ := strconv.Atoi(dims[1])
		l, _ := strconv.Atoi(dims[2])
		m.WidthMm, m.LengthMm = float64(w), float64(l)
		// Two distinct dimensions and no "continuous" marker means pre-cut labels.
		m.DieCut = !continuous
	} else if w := regexp.MustCompile(`(\d+)mm`).FindStringSubmatch(lower); w != nil {
		n, _ := strconv.Atoi(w[1])
		m.WidthMm = float64(n)
	}

	if !m.DieCut {
		m.LengthMm = 0 // continuous tape has no fixed length
	}
	if m.WidthMm == 0 {
		return nil // couldn't make sense of it; treat as unknown
	}
	return m
}
