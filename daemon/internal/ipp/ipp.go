// Package ipp is a minimal IPP (RFC 8010/8011) client, standard library only.
//
// It serves both printer families the daemon supports, because both speak IPP even though they
// print over completely different channels:
//
//   - Brother QL (network): IPP on port 631 is the only way to read status and the media actually
//     loaded. Its raw port 9100 is write-only -- it accepts a print stream but never answers an
//     ESC i S status request -- so a job written there "succeeds" even while the printer flashes an
//     error. Printing itself does not go through IPP.
//   - Dymo LabelWriter (USB): reached through the local CUPS queue, where IPP does everything --
//     status, the media geometry, the queue list, and the print job itself.
//
// Implemented operations: Get-Printer-Attributes, Print-Job and the CUPS extension
// CUPS-Get-Printers.
package ipp

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// IPP delimiter tags (RFC 8010 3.5.1). Anything below 0x10 delimits an attribute group.
const (
	tagOperationAttributes = 0x01
	tagJobAttributes       = 0x02
	tagEndOfAttributes     = 0x03
	tagDelimiterMax        = 0x0F
)

// IPP value tags used here.
const (
	tagInteger        = 0x21
	tagBoolean        = 0x22
	tagEnum           = 0x23
	tagResolution     = 0x32
	tagBegCollection  = 0x34
	tagEndCollection  = 0x37
	tagNameWithoutLng = 0x42
	tagKeyword        = 0x44
	tagURI            = 0x45
	tagCharset        = 0x47
	tagLanguage       = 0x48
	tagMimeMediaType  = 0x49
	tagMemberAttrName = 0x4A
)

const (
	opPrintJob             = 0x0002
	opGetPrinterAttributes = 0x000B
	opCUPSGetPrinters      = 0x4002
)

// Value is one attribute value: either a scalar, rendered as text, or a collection such as
// media-col, whose members are themselves Values (collections nest -- media-col holds media-size).
type Value struct {
	S    string
	Coll map[string]*Value
}

// Int parses a scalar value as an integer, returning 0 when it is not one.
func (v *Value) Int() int {
	if v == nil {
		return 0
	}
	n, _ := strconv.Atoi(v.S)
	return n
}

// Member returns a named member of a collection value, or nil.
func (v *Value) Member(name string) *Value {
	if v == nil || v.Coll == nil {
		return nil
	}
	return v.Coll[name]
}

// Attrs is a whole IPP response, flattened to attribute name -> its values.
type Attrs map[string][]*Value

// First returns the first value of an attribute, or nil when it is absent.
func (a Attrs) First(name string) *Value {
	if v := a[name]; len(v) > 0 {
		return v[0]
	}
	return nil
}

// Str returns the first value of an attribute as text, or "".
func (a Attrs) Str(name string) string {
	if v := a.First(name); v != nil {
		return v.S
	}
	return ""
}

// ---------------------------------------------------------------- encoding

func writeAttr(b *bytes.Buffer, tag byte, name, value string) {
	b.WriteByte(tag)
	binary.Write(b, binary.BigEndian, uint16(len(name)))
	b.WriteString(name)
	binary.Write(b, binary.BigEndian, uint16(len(value)))
	b.WriteString(value)
}

// writeHeader starts a request: version, operation, request id, then the mandatory operation
// attributes every IPP request must open with.
func writeHeader(b *bytes.Buffer, op uint16) {
	b.Write([]byte{0x02, 0x00}) // IPP 2.0
	binary.Write(b, binary.BigEndian, op)
	binary.Write(b, binary.BigEndian, uint32(1)) // request-id
	b.WriteByte(tagOperationAttributes)
	writeAttr(b, tagCharset, "attributes-charset", "utf-8")
	writeAttr(b, tagLanguage, "attributes-natural-language", "en")
}

// writeRequestedAttributes emits a 1setOf keyword: only the first value carries the attribute
// name, the rest have an empty name (RFC 8010 3.1.4).
func writeRequestedAttributes(b *bytes.Buffer, wanted []string) {
	for i, name := range wanted {
		if i == 0 {
			writeAttr(b, tagKeyword, "requested-attributes", name)
		} else {
			writeAttr(b, tagKeyword, "", name)
		}
	}
}

// timeout is generous because a Print-Job carries the whole raster and the spooler may be busy.
func do(uri string, body []byte, timeout time.Duration) ([]byte, error) {
	req, err := http.NewRequest(http.MethodPost, uri, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/ipp")

	resp, err := (&http.Client{Timeout: timeout}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("ipp %s: HTTP %s", uri, resp.Status)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 4<<20))
}

// ---------------------------------------------------------------- decoding

type parser struct {
	raw []byte
	i   int
}

func (p *parser) ok(n int) bool { return p.i+n <= len(p.raw) }

func (p *parser) u16() int {
	v := int(binary.BigEndian.Uint16(p.raw[p.i:]))
	p.i += 2
	return v
}

func (p *parser) bytes(n int) []byte {
	v := p.raw[p.i : p.i+n]
	p.i += n
	return v
}

// parseCollection consumes members up to the matching endCollection. RFC 8010 3.1.6: a member is a
// memberAttrName attribute naming it, followed by the value attribute holding it; both have an
// empty attribute name, and a member may itself be a collection.
func (p *parser) parseCollection() map[string]*Value {
	out := map[string]*Value{}
	member := ""
	for p.ok(3) {
		tag := p.raw[p.i]
		p.i++
		nameLen := p.u16()
		if !p.ok(nameLen) {
			break
		}
		p.bytes(nameLen) // member attributes carry no name of their own
		if tag == tagEndCollection {
			if p.ok(2) {
				p.u16()
			}
			return out
		}
		if !p.ok(2) {
			break
		}
		valLen := p.u16()
		if tag == tagBegCollection {
			out[member] = &Value{Coll: p.parseCollection()}
			continue
		}
		if !p.ok(valLen) {
			break
		}
		v := p.bytes(valLen)
		if tag == tagMemberAttrName {
			member = string(v)
			continue
		}
		out[member] = &Value{S: decodeScalar(tag, v)}
	}
	return out
}

func decodeScalar(tag byte, value []byte) string {
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
	case tagResolution:
		if len(value) == 9 {
			return fmt.Sprintf("%dx%d", binary.BigEndian.Uint32(value[0:4]), binary.BigEndian.Uint32(value[4:8]))
		}
	}
	return string(value)
}

// parseResponse flattens an IPP response into attribute name -> values. Group boundaries are
// dropped: no attribute this daemon reads is ambiguous across groups.
func parseResponse(raw []byte) (Attrs, error) {
	if len(raw) < 8 {
		return nil, fmt.Errorf("ipp response too short (%d bytes)", len(raw))
	}
	// IPP "successful-*" status codes are 0x0000-0x00FF.
	if code := binary.BigEndian.Uint16(raw[2:4]); code > 0x00FF {
		return nil, fmt.Errorf("ipp error status 0x%04X", code)
	}

	p := &parser{raw: raw, i: 8}
	out := Attrs{}
	lastName := ""
	for p.ok(1) {
		tag := p.raw[p.i]
		p.i++
		if tag == tagEndOfAttributes {
			break
		}
		if tag <= tagDelimiterMax {
			continue // start of an attribute group
		}
		if !p.ok(2) {
			break
		}
		nameLen := p.u16()
		if !p.ok(nameLen) {
			break
		}
		name := string(p.bytes(nameLen))
		if nameLen == 0 {
			name = lastName // additional value for the previous attribute
		} else {
			lastName = name
		}
		if !p.ok(2) {
			break
		}
		valLen := p.u16()
		if tag == tagBegCollection {
			out[name] = append(out[name], &Value{Coll: p.parseCollection()})
			continue
		}
		if !p.ok(valLen) {
			break
		}
		out[name] = append(out[name], &Value{S: decodeScalar(tag, p.bytes(valLen))})
	}
	return out, nil
}

// ---------------------------------------------------------------- operations

// GetPrinterAttributes asks a printer (or a CUPS queue) for the named attributes.
func GetPrinterAttributes(uri string, wanted []string) (Attrs, error) {
	var b bytes.Buffer
	writeHeader(&b, opGetPrinterAttributes)
	writeAttr(&b, tagURI, "printer-uri", uri)
	writeRequestedAttributes(&b, wanted)
	b.WriteByte(tagEndOfAttributes)

	raw, err := do(uri, b.Bytes(), 10*time.Second)
	if err != nil {
		return nil, err
	}
	return parseResponse(raw)
}

// PrintJob submits a document. The media keyword, when given, tells the spooler which label stock
// the job is formatted for -- required for a printer that cannot sense its own roll.
func PrintJob(uri, user, jobName, docFormat, media string, doc []byte) error {
	var b bytes.Buffer
	writeHeader(&b, opPrintJob)
	writeAttr(&b, tagURI, "printer-uri", uri)
	writeAttr(&b, tagNameWithoutLng, "requesting-user-name", user)
	writeAttr(&b, tagNameWithoutLng, "job-name", jobName)
	writeAttr(&b, tagMimeMediaType, "document-format", docFormat)
	b.WriteByte(tagJobAttributes)
	if media != "" {
		writeAttr(&b, tagKeyword, "media", media)
	}
	b.WriteByte(tagEndOfAttributes)
	b.Write(doc)

	raw, err := do(uri, b.Bytes(), 60*time.Second)
	if err != nil {
		return err
	}
	// A non-success status comes back as an error from parseResponse, so reaching here means the
	// spooler accepted the job. It has not printed yet; the caller re-checks status afterwards.
	if _, err := parseResponse(raw); err != nil {
		return err
	}
	return nil
}

// Queue is one print queue offered by a local CUPS server.
type Queue struct {
	Name         string
	URI          string
	MakeAndModel string
	Info         string
}

// CUPSGetPrinters lists the queues a CUPS server holds. This is a CUPS extension operation, not
// part of IPP proper, and is addressed to the server root rather than to any one queue.
func CUPSGetPrinters(serverURI string) ([]Queue, error) {
	var b bytes.Buffer
	writeHeader(&b, opCUPSGetPrinters)
	writeAttr(&b, tagURI, "printer-uri", serverURI)
	writeRequestedAttributes(&b, []string{
		"printer-name", "printer-uri-supported", "printer-make-and-model", "printer-info",
	})
	b.WriteByte(tagEndOfAttributes)

	raw, err := do(serverURI, b.Bytes(), 10*time.Second)
	if err != nil {
		return nil, err
	}
	attrs, err := parseResponse(raw)
	if err != nil {
		return nil, err
	}

	// One value per queue, positionally aligned across the four attributes.
	names := attrs["printer-name"]
	out := make([]Queue, 0, len(names))
	at := func(name string, i int) string {
		if v := attrs[name]; i < len(v) {
			return v[i].S
		}
		return ""
	}
	for i, n := range names {
		out = append(out, Queue{
			Name:         n.S,
			URI:          at("printer-uri-supported", i),
			MakeAndModel: at("printer-make-and-model", i),
			Info:         at("printer-info", i),
		})
	}
	return out, nil
}
