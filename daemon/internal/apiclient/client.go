// Package apiclient talks to the Clele backend's /api/daemon/** endpoints.
package apiclient

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/clele/print-daemon/internal/ipp"
	"github.com/clele/print-daemon/internal/printer"
)

type Client struct {
	BackendURL string
	DaemonID   int64
	APIKey     string
	Version    string
	HTTP       *http.Client
}

func New(backendURL string, daemonID int64, apiKey, version string) *Client {
	return &Client{
		BackendURL: normalizeBaseURL(backendURL),
		DaemonID:   daemonID,
		APIKey:     apiKey,
		Version:    version,
		HTTP:       &http.Client{Timeout: 40 * time.Second},
	}
}

type RegisterResponse struct {
	DaemonID int64  `json:"daemonId"`
	APIKey   string `json:"apiKey"`
}

// normalizeBaseURL drops any trailing slashes so joining paths can't produce "//api/...".
func normalizeBaseURL(u string) string {
	return strings.TrimRight(strings.TrimSpace(u), "/")
}

// Register self-registers a new daemon; unauthenticated (no key exists yet).
func Register(backendURL, hostname, version string) (*RegisterResponse, error) {
	backendURL = normalizeBaseURL(backendURL)
	body, _ := json.Marshal(map[string]string{"hostname": hostname})
	req, err := http.NewRequest(http.MethodPost, backendURL+"/api/daemon/register", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Daemon-Version", version)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("register failed: %s", resp.Status)
	}
	var out RegisterResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

type Job struct {
	JobID          int64  `json:"jobId"`
	LabelPngBase64 string `json:"labelPngBase64"`
	PrinterIP      string `json:"printerIp"`
	PrinterType    string `json:"printerType"`
	PrinterQueue   string `json:"printerQueue"`
	MediaKeyword   string `json:"mediaKeyword"`
}

// Target is where this job should be printed. Taken from the job itself rather than from the last
// poll's headers, so a configuration change racing a queued job cannot make the daemon print it
// with settings the job was not formatted for.
func (j *Job) Target() printer.Target {
	return printer.Target{
		Type:         j.PrinterType,
		IP:           j.PrinterIP,
		Queue:        j.PrinterQueue,
		MediaKeyword: j.MediaKeyword,
	}
}

// Poll is one long-poll result: the job if one arrived, plus the printer configuration the backend
// holds for this daemon, which it returns on every poll -- job or not -- so the daemon can probe
// the printer before any job exists.
type Poll struct {
	Job    *Job
	Target printer.Target
	// WantCapabilities is set when the backend has no discovered queue/media list for the current
	// target and would like one pushed.
	WantCapabilities bool
}

// NextJob long-polls for the next queued job. report, when known, is sent along so the web app can
// size labels to the printer's actual printable area and show what is loaded.
func (c *Client) NextJob(waitSeconds int, report *printer.Report) (Poll, error) {
	var out Poll
	url := fmt.Sprintf("%s/api/daemon/jobs/next?wait=%d", c.BackendURL, waitSeconds)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return out, err
	}
	c.authenticate(req)
	setReportHeaders(req, report)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return out, err
	}
	defer resp.Body.Close()

	out.Target = printer.Target{
		Type:         resp.Header.Get("X-Printer-Type"),
		IP:           resp.Header.Get("X-Printer-Ip"),
		Queue:        resp.Header.Get("X-Printer-Queue"),
		MediaKeyword: resp.Header.Get("X-Printer-Media"),
	}
	out.WantCapabilities = resp.Header.Get("X-Capabilities-Wanted") != ""

	if resp.StatusCode == http.StatusNoContent {
		return out, nil
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return out, fmt.Errorf("poll failed: %s: %s", resp.Status, string(b))
	}
	var j Job
	if err := json.NewDecoder(resp.Body).Decode(&j); err != nil {
		return out, err
	}
	out.Job = &j
	return out, nil
}

// ReportCapabilities pushes the queues and label stock this machine offers. Sent separately from
// the poll because the media list runs to dozens of entries -- far too large for a header -- and
// changes only when the machine's printer setup does.
func (c *Client) ReportCapabilities(caps *printer.Capabilities) error {
	body, err := json.Marshal(caps)
	if err != nil {
		return err
	}
	url := c.BackendURL + "/api/daemon/capabilities"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	c.authenticate(req)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("capabilities report failed: %s: %s", resp.Status, string(b))
	}
	return nil
}

// Complete reports the outcome of a delivered job.
func (c *Client) Complete(jobID int64, status string, errMsg string) error {
	body, _ := json.Marshal(map[string]string{"status": status, "error": errMsg})
	url := fmt.Sprintf("%s/api/daemon/jobs/%d/complete", c.BackendURL, jobID)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	c.authenticate(req)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("complete failed: %s", resp.Status)
	}
	return nil
}

// setReportHeaders reports what the daemon found at the printer. Sent on every poll so the app
// reflects a media change (a different label roll) without any user action.
//
// Measurements go out as decimal strings, not integers: Dymo stock is sized in inches and a common
// roll is 19.05 x 50.8 mm, which whole millimetres cannot express.
func setReportHeaders(req *http.Request, report *printer.Report) {
	if report == nil {
		return
	}
	if report.Model != "" {
		req.Header.Set("X-Printer-Model", report.Model)
	}
	if report.PrintableWidthMm > 0 {
		req.Header.Set("X-Printer-Printable-Width", ipp.Mm(report.PrintableWidthMm))
	}
	if report.PrintableLengthMm > 0 {
		req.Header.Set("X-Printer-Printable-Length", ipp.Mm(report.PrintableLengthMm))
	}
	media := report.Media
	if media == nil {
		return
	}
	kind := "CONTINUOUS"
	if media.DieCut {
		kind = "DIE_CUT"
	}
	req.Header.Set("X-Printer-Media-Kind", kind)
	req.Header.Set("X-Printer-Media-Width", ipp.Mm(media.WidthMm))
	req.Header.Set("X-Printer-Media-Length", ipp.Mm(media.LengthMm))
	req.Header.Set("X-Printer-Media-Name", media.Name)
}

func (c *Client) authenticate(req *http.Request) {
	req.Header.Set("X-Daemon-Id", fmt.Sprintf("%d", c.DaemonID))
	req.Header.Set("X-Daemon-Key", c.APIKey)
	// Reported on every call so the app always reflects the version actually running, including
	// after an in-place binary upgrade (no re-registration needed).
	req.Header.Set("X-Daemon-Version", c.Version)
}
