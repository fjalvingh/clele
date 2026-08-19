// Package printer is the seam between the daemon's job loop and the printer families it can drive.
//
// It holds only data types and the Driver interface, so that every driver can import it without a
// cycle. Choosing a driver for a target is a composition concern and lives in main, not here.
package printer

import "github.com/clele/print-daemon/internal/ipp"

// Printer type identifiers. These travel from the backend on every poll as X-Printer-Type and are
// the string form of the PrinterType enum stored against the daemon.
const (
	TypeBrotherQL = "BROTHER_QL"
	TypeDymoCUPS  = "DYMO_CUPS"
)

// Target is where a daemon should print, as configured in the web app. Which fields matter depends
// on Type: a Brother QL is on the network and needs IP; a Dymo is on USB behind a local CUPS queue
// and needs Queue, plus MediaKeyword because it cannot sense which roll is loaded.
type Target struct {
	Type         string
	IP           string
	Queue        string
	MediaKeyword string
}

// Empty reports whether nothing at all was configured — which in practice means a poll that never
// reached the backend, not a daemon whose printer is unset (the backend always states a type).
func (t Target) Empty() bool { return t == Target{} }

// Configured reports whether the target has everything its type needs to print.
func (t Target) Configured() bool {
	switch t.Type {
	case TypeDymoCUPS:
		return t.Queue != "" && t.MediaKeyword != ""
	default:
		return t.IP != ""
	}
}

// Missing describes what a target still needs, for an error the user will read in the web app.
func (t Target) Missing() string {
	switch t.Type {
	case TypeDymoCUPS:
		if t.Queue == "" {
			return "no CUPS print queue selected for this daemon"
		}
		return "no label size selected for this printer"
	default:
		return "no printer address configured for this daemon"
	}
}

// Report is what the daemon tells the backend about the printer after probing it.
//
// PrintableWidthMm/PrintableLengthMm are the area the printer can actually mark, and are the whole
// point of the type: each driver knows its own geometry (the Brother from constants measured on the
// hardware, the Dymo from the margins CUPS reports), so the frontend can size a label from one
// reported number instead of mirroring per-printer constants it cannot verify.
type Report struct {
	Model             string
	Media             *ipp.Media
	PrintableWidthMm  float64
	PrintableLengthMm float64
}

// MediaOption is one label stock the printer offers, for the picker in the web app.
type MediaOption struct {
	Keyword           string  `json:"keyword"`
	DisplayName       string  `json:"displayName,omitempty"`
	WidthMm           float64 `json:"widthMm"`
	LengthMm          float64 `json:"lengthMm,omitempty"`
	PrintableWidthMm  float64 `json:"printableWidthMm"`
	PrintableLengthMm float64 `json:"printableLengthMm,omitempty"`
}

// Queue is one print queue the daemon found on the machine it runs on.
type Queue struct {
	Name         string        `json:"name"`
	Description  string        `json:"description,omitempty"`
	MakeAndModel string        `json:"makeAndModel,omitempty"`
	Media        []MediaOption `json:"media"`
}

// Capabilities is everything discoverable about the printers available to this daemon. It is
// pushed to the backend in one go rather than reported on the poll: the media list runs to dozens
// of entries, far too large for a response header, and it changes only when the machine's printer
// setup does.
type Capabilities struct {
	Queues []Queue `json:"queues"`
}

// Driver drives one printer family.
type Driver interface {
	// Probe reads the printer's current state and geometry.
	Probe() (*ipp.PrinterStatus, *Report, error)
	// Capabilities lists what the daemon could be pointed at. Returns nil for a printer family
	// with nothing to discover, such as a network printer reached by an address the user types.
	Capabilities() (*Capabilities, error)
	// Print sends a label, rendered as a PNG whose x axis is the feed direction.
	Print(png []byte) error
}
