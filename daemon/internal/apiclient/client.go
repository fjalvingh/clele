// Package apiclient talks to the Clele backend's /api/daemon/** endpoints.
package apiclient

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/clele/print-daemon/internal/ipp"
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
}

// NextJob long-polls for the next queued job; job is nil if none arrived within waitSeconds.
// media, when known, is reported alongside so the web app can size labels to the loaded stock.
// The returned printerIP is the address configured for this daemon in the web app — reported on
// every poll (job or not) so the daemon can query the printer before any job exists.
func (c *Client) NextJob(waitSeconds int, media *ipp.Media) (job *Job, printerIP string, err error) {
	url := fmt.Sprintf("%s/api/daemon/jobs/next?wait=%d", c.BackendURL, waitSeconds)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, "", err
	}
	c.authenticate(req)
	setMediaHeaders(req, media)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()

	printerIP = resp.Header.Get("X-Printer-Ip")
	if resp.StatusCode == http.StatusNoContent {
		return nil, printerIP, nil
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, printerIP, fmt.Errorf("poll failed: %s: %s", resp.Status, string(b))
	}
	var j Job
	if err := json.NewDecoder(resp.Body).Decode(&j); err != nil {
		return nil, printerIP, err
	}
	return &j, printerIP, nil
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

// setMediaHeaders reports the media currently loaded in the printer. Sent on every poll so the
// app reflects a media change (a different label roll) without any user action.
func setMediaHeaders(req *http.Request, media *ipp.Media) {
	if media == nil {
		return
	}
	kind := "CONTINUOUS"
	if media.DieCut {
		kind = "DIE_CUT"
	}
	req.Header.Set("X-Printer-Media-Kind", kind)
	req.Header.Set("X-Printer-Media-Width", strconv.Itoa(media.WidthMm))
	req.Header.Set("X-Printer-Media-Length", strconv.Itoa(media.LengthMm))
	req.Header.Set("X-Printer-Media-Name", media.Name)
}

func (c *Client) authenticate(req *http.Request) {
	req.Header.Set("X-Daemon-Id", fmt.Sprintf("%d", c.DaemonID))
	req.Header.Set("X-Daemon-Key", c.APIKey)
	// Reported on every call so the app always reflects the version actually running, including
	// after an in-place binary upgrade (no re-registration needed).
	req.Header.Set("X-Daemon-Version", c.Version)
}
