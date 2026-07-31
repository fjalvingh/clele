// Package ipp is a minimal IPP (RFC 8010/8011) client for reading printer status and the media
// actually loaded. Only Get-Printer-Attributes is implemented, using the standard library only.
//
// Why IPP rather than the Brother raster status command: the QL-710W's raw port 9100 is
// write-only — it accepts a print stream but never answers an ESC i S status request. IPP on port
// 631 does answer, and reports the loaded media exactly (e.g. 17x54mm die-cut labels), which is
// what the job's print-information command must declare to avoid a media-mismatch error.
package ipp

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// IPP delimiter tags (RFC 8010 §3.5.1). Anything below 0x10 delimits an attribute group.
const (
	tagOperationAttributes = 0x01
	tagEndOfAttributes     = 0x03
	tagDelimiterMax        = 0x0F
)

// IPP value tags used here.
const (
	tagInteger  = 0x21
	tagBoolean  = 0x22
	tagEnum     = 0x23
	tagKeyword  = 0x44
	tagURI      = 0x45
	tagCharset  = 0x47
	tagLanguage = 0x48
)

const opGetPrinterAttributes = 0x000B

// printer-state enum values (RFC 8011 §5.4.11).
const (
	stateIdle       = 3
	stateProcessing = 4
	stateStopped    = 5
)

// Endpoint paths vary by vendor; these cover Brother and the common defaults.
var candidatePaths = []string{"/ipp/print", "/ipp/port1", "/"}

// Media is the label stock currently loaded in the printer.
type Media struct {
	// Name is the raw IPP media keyword, e.g. "om_brother-label-17x54mm_17x54mm".
	Name string
	// WidthMm is the dimension across the print head (the narrow edge of the label).
	WidthMm int
	// LengthMm is the dimension along the feed direction. 0 for continuous stock, which has no
	// fixed length.
	LengthMm int
	// DieCut distinguishes pre-cut labels (fixed length) from continuous tape.
	DieCut bool
}

func (m *Media) String() string {
	if m == nil {
		return "unknown media"
	}
	if m.DieCut {
		return fmt.Sprintf("%d x %d mm die-cut labels", m.WidthMm, m.LengthMm)
	}
	return fmt.Sprintf("%d mm continuous tape", m.WidthMm)
}

// PrinterStatus is the subset of printer attributes this daemon acts on.
type PrinterStatus struct {
	State         string // "idle", "processing", "stopped" or "unknown"
	StateReasons  []string
	StateMessage  string
	AcceptingJobs bool
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

// GetPrinterStatus queries the printer over IPP, trying the usual endpoint paths in turn.
func GetPrinterStatus(printerIP string) (*PrinterStatus, error) {
	var lastErr error
	for _, path := range candidatePaths {
		status, err := getPrinterStatusAt(printerIP, path)
		if err == nil {
			return status, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func getPrinterStatusAt(printerIP, path string) (*PrinterStatus, error) {
	body := buildGetPrinterAttributes(printerIP, path)

	req, err := http.NewRequest(http.MethodPost, "http://"+printerIP+":631"+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/ipp")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("ipp %s: HTTP %s", path, resp.Status)
	}

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	return parseGetPrinterAttributes(raw)
}

func buildGetPrinterAttributes(printerIP, path string) []byte {
	var b bytes.Buffer
	b.Write([]byte{0x02, 0x00}) // version 2.0
	binary.Write(&b, binary.BigEndian, uint16(opGetPrinterAttributes))
	binary.Write(&b, binary.BigEndian, uint32(1)) // request-id
	b.WriteByte(tagOperationAttributes)

	writeAttr(&b, tagCharset, "attributes-charset", "utf-8")
	writeAttr(&b, tagLanguage, "attributes-natural-language", "en")
	writeAttr(&b, tagURI, "printer-uri", "ipp://"+printerIP+path)

	wanted := []string{
		"printer-state",
		"printer-state-reasons",
		"printer-state-message",
		"printer-is-accepting-jobs",
		"media-ready",
	}
	for i, name := range wanted {
		if i == 0 {
			writeAttr(&b, tagKeyword, "requested-attributes", name)
		} else {
			// Additional values of the same attribute carry an empty name (RFC 8010 §3.1.4).
			writeAttr(&b, tagKeyword, "", name)
		}
	}

	b.WriteByte(tagEndOfAttributes)
	return b.Bytes()
}

func writeAttr(b *bytes.Buffer, tag byte, name, value string) {
	b.WriteByte(tag)
	binary.Write(b, binary.BigEndian, uint16(len(name)))
	b.WriteString(name)
	binary.Write(b, binary.BigEndian, uint16(len(value)))
	b.WriteString(value)
}

// attribute values collected from the response, keyed by attribute name.
type attrs map[string][]string

func parseGetPrinterAttributes(raw []byte) (*PrinterStatus, error) {
	if len(raw) < 8 {
		return nil, fmt.Errorf("ipp response too short (%d bytes)", len(raw))
	}
	statusCode := binary.BigEndian.Uint16(raw[2:4])
	// IPP "successful-*" status codes are 0x0000-0x00FF.
	if statusCode > 0x00FF {
		return nil, fmt.Errorf("ipp error status 0x%04X", statusCode)
	}

	collected := attrs{}
	var lastName string
	i := 8
	for i < len(raw) {
		tag := raw[i]
		i++
		if tag == tagEndOfAttributes {
			break
		}
		if tag <= tagDelimiterMax {
			continue // start of an attribute group
		}
		if i+2 > len(raw) {
			break
		}
		nameLen := int(binary.BigEndian.Uint16(raw[i : i+2]))
		i += 2
		if i+nameLen > len(raw) {
			break
		}
		name := string(raw[i : i+nameLen])
		i += nameLen

		if i+2 > len(raw) {
			break
		}
		valueLen := int(binary.BigEndian.Uint16(raw[i : i+2]))
		i += 2
		if i+valueLen > len(raw) {
			break
		}
		value := raw[i : i+valueLen]
		i += valueLen

		if nameLen == 0 {
			name = lastName // additional value for the previous attribute
		} else {
			lastName = name
		}
		collected[name] = append(collected[name], decodeValue(tag, value))
	}

	return statusFromAttrs(collected), nil
}

func decodeValue(tag byte, value []byte) string {
	switch tag {
	case tagInteger, tagEnum:
		if len(value) == 4 {
			return strconv.Itoa(int(int32(binary.BigEndian.Uint32(value))))
		}
	case tagBoolean:
		if len(value) == 1 && value[0] == 1 {
			return "true"
		}
		return "false"
	}
	return string(value)
}

func statusFromAttrs(a attrs) *PrinterStatus {
	s := &PrinterStatus{State: "unknown"}

	if v := first(a["printer-state"]); v != "" {
		switch v {
		case strconv.Itoa(stateIdle):
			s.State = "idle"
		case strconv.Itoa(stateProcessing):
			s.State = "processing"
		case strconv.Itoa(stateStopped):
			s.State = "stopped"
		}
	}
	s.StateReasons = a["printer-state-reasons"]
	s.StateMessage = first(a["printer-state-message"])
	s.AcceptingJobs = first(a["printer-is-accepting-jobs"]) == "true"
	s.Media = parseMedia(first(a["media-ready"]))
	return s
}

func first(v []string) string {
	if len(v) == 0 {
		return ""
	}
	return v[0]
}

// mediaDimsRe matches the trailing dimensions of an IPP media keyword, e.g. the "17x54mm" in
// "om_brother-label-17x54mm_17x54mm".
var mediaDimsRe = regexp.MustCompile(`(\d+)x(\d+)mm`)

// parseMedia turns an IPP media keyword into concrete dimensions. Brother names die-cut stock
// with both dimensions ("...-label-17x54mm...") and continuous stock with only a width.
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
		m.WidthMm, m.LengthMm = w, l
		// Two distinct dimensions and no "continuous" marker means pre-cut labels.
		m.DieCut = !continuous
	} else if w := regexp.MustCompile(`(\d+)mm`).FindStringSubmatch(lower); w != nil {
		m.WidthMm, _ = strconv.Atoi(w[1])
	}

	if !m.DieCut {
		m.LengthMm = 0 // continuous tape has no fixed length
	}
	if m.WidthMm == 0 {
		return nil // couldn't make sense of it; treat as unknown
	}
	return m
}
