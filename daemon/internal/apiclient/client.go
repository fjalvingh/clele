// Package apiclient talks to the Clele backend's /api/daemon/** endpoints.
package apiclient

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
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
		BackendURL: backendURL,
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

// Register self-registers a new daemon; unauthenticated (no key exists yet).
func Register(backendURL, hostname, version string) (*RegisterResponse, error) {
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
	TapeWidthMm    int    `json:"tapeWidthMm"`
}

// NextJob long-polls for the next queued job; returns nil, nil if none arrived within waitSeconds.
func (c *Client) NextJob(waitSeconds int) (*Job, error) {
	url := fmt.Sprintf("%s/api/daemon/jobs/next?wait=%d", c.BackendURL, waitSeconds)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	c.authenticate(req)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent {
		return nil, nil
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("poll failed: %s: %s", resp.Status, string(b))
	}
	var job Job
	if err := json.NewDecoder(resp.Body).Decode(&job); err != nil {
		return nil, err
	}
	return &job, nil
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

func (c *Client) authenticate(req *http.Request) {
	req.Header.Set("X-Daemon-Id", fmt.Sprintf("%d", c.DaemonID))
	req.Header.Set("X-Daemon-Key", c.APIKey)
	// Reported on every call so the app always reflects the version actually running, including
	// after an in-place binary upgrade (no re-registration needed).
	req.Header.Set("X-Daemon-Version", c.Version)
}
