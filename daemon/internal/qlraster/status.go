package qlraster

// Media type values used in the print-information command (ESC i z).
//
// Note: the Brother raster protocol also defines a status-request command (ESC i S) that returns
// a 32-byte status packet. It is deliberately not implemented here — the QL-710W's raw port 9100
// is write-only and never answers it (verified against the hardware: no reply to ESC i S with or
// without an invalidate/initialize preamble). Printer state and loaded media are read over IPP
// instead; see internal/ipp.
const (
	MediaNone       = 0x00
	MediaContinuous = 0x0A
	MediaDieCut     = 0x0B
)
