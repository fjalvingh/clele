package qlraster

import (
	"github.com/clele/print-daemon/internal/ipp"
	"github.com/clele/print-daemon/internal/printer"
)

// Driver adapts the Brother QL raster path to the daemon's printer.Driver seam. All the
// hardware-specific work stays in raster.go; this only exposes it in the shape the job loop wants
// and answers for the printer's geometry.
type Driver struct{ IP string }

func NewDriver(ip string) *Driver { return &Driver{IP: ip} }

// Probe reads state and the media the printer reports over IPP.
func (d *Driver) Probe() (*ipp.PrinterStatus, *printer.Report, error) {
	status, err := ipp.GetPrinterStatus(d.IP)
	if err != nil {
		return nil, nil, err
	}
	report := &printer.Report{Model: status.MakeAndModel, Media: status.Media}
	if status.Media != nil {
		w, l := PrintableArea(*status.Media)
		report.PrintableWidthMm, report.PrintableLengthMm = w, l
	}
	return status, report, nil
}

// Capabilities returns nothing: a network printer is reached by an address the user types, and
// there is no local list to discover.
func (d *Driver) Capabilities() (*printer.Capabilities, error) { return nil, nil }

func (d *Driver) Print(pngBytes []byte) error {
	_, err := Print(d.IP, pngBytes)
	return err
}

// PrintableArea is the part of a label this printer can actually mark, derived from the geometry
// measured on the hardware (see the constants in raster.go). Reporting it to the backend is what
// lets the web app size a label without mirroring these constants in the frontend, where they
// could not be verified and silently drifted.
func PrintableArea(media ipp.Media) (widthMm, lengthMm float64) {
	widthMm = media.WidthMm - unprintableEdgeMm
	if widthMm < 0 {
		widthMm = 0
	}
	if !media.DieCut || media.LengthMm <= 0 {
		return widthMm, 0 // continuous tape: the length is the caller's to choose
	}
	lengthMm = media.LengthMm - dieCutLeadMm
	if lengthMm < 0 {
		lengthMm = 0
	}
	return widthMm, lengthMm
}
