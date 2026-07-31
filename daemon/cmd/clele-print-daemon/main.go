// clele-print-daemon prints labels pushed from the Clele web app to a network-connected Brother
// QL-710W, without any user interaction. See daemon/README.md for install/usage.
package main

import (
	"encoding/base64"
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/clele/print-daemon/internal/apiclient"
	"github.com/clele/print-daemon/internal/config"
	"github.com/clele/print-daemon/internal/ipp"
	"github.com/clele/print-daemon/internal/qlraster"
)

// version is injected at build time by the Maven build
// (-ldflags "-X main.version=…") from the timestamp of the last commit touching daemon/, so it
// only changes when the daemon itself changes. "unknown" when built outside that build.
var version = "unknown"

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: clele-print-daemon <register|run|status|version> [flags]")
		os.Exit(1)
	}

	switch os.Args[1] {
	case "register":
		cmdRegister(os.Args[2:])
	case "run":
		cmdRun(os.Args[2:])
	case "status":
		cmdStatus(os.Args[2:])
	case "version", "--version", "-version":
		fmt.Println(version)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n", os.Args[1])
		os.Exit(1)
	}
}

func cmdRegister(args []string) {
	fs := flag.NewFlagSet("register", flag.ExitOnError)
	backendURL := fs.String("backend-url", "", "Clele backend base URL, e.g. https://clele.example.com")
	configPath := fs.String("config", config.DefaultPath, "path to write the daemon config file")
	fs.Parse(args)

	if *backendURL == "" {
		log.Fatal("--backend-url is required")
	}
	hostname, _ := os.Hostname()

	resp, err := apiclient.Register(*backendURL, hostname, version)
	if err != nil {
		log.Fatalf("registration failed: %v", err)
	}
	err = config.Save(*configPath, &config.Config{
		BackendURL: *backendURL,
		DaemonID:   resp.DaemonID,
		APIKey:     resp.APIKey,
	})
	if err != nil {
		log.Fatalf("failed to write config: %v", err)
	}
	log.Printf("Registered as daemon #%d. Claim it from the Clele Settings page, then start the service.", resp.DaemonID)
}

func cmdRun(args []string) {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	configPath := fs.String("config", config.DefaultPath, "path to the daemon config file")
	fs.Parse(args)

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("failed to load config %s (run 'clele-print-daemon register' first): %v", *configPath, err)
	}

	client := apiclient.New(cfg.BackendURL, cfg.DaemonID, cfg.APIKey, version)
	log.Printf("clele-print-daemon #%d (version %s) started, polling %s", cfg.DaemonID, version, cfg.BackendURL)

	// Cached media, refreshed periodically and reported on each poll so the web app can size
	// labels to the stock actually loaded. Detection needs the printer address, which the backend
	// returns with every poll.
	var media *ipp.Media
	var mediaCheckedAt time.Time
	const mediaTTL = time.Minute

	var printerIP string
	for {
		if printerIP != "" && time.Since(mediaCheckedAt) > mediaTTL {
			mediaCheckedAt = time.Now()
			if status, err := ipp.GetPrinterStatus(printerIP); err != nil {
				log.Printf("could not read printer status from %s: %v", printerIP, err)
			} else {
				if media == nil || status.Media == nil || *status.Media != *media {
					log.Printf("printer %s: %s (%s)", printerIP, status.Media, status.State)
				}
				media = status.Media
			}
		}

		job, ip, err := client.NextJob(25, media)
		if ip != "" && ip != printerIP {
			printerIP = ip
			mediaCheckedAt = time.Time{} // new printer address, re-detect immediately
		}
		if err != nil {
			log.Printf("poll error: %v", err)
			continue
		}
		if job == nil {
			continue
		}
		if err := printJob(client, job); err != nil {
			log.Printf("job %d failed: %v", job.JobID, err)
		}
	}
}

// cmdStatus queries a printer and prints what it reports — media loaded, error state, raw packet.
// Diagnostic aid for "the printer shows an error but I can't tell why".
func cmdStatus(args []string) {
	fs := flag.NewFlagSet("status", flag.ExitOnError)
	printerIP := fs.String("printer-ip", "", "printer IP address (as configured on the Settings page)")
	fs.Parse(args)

	ip := *printerIP
	if ip == "" {
		log.Fatal("--printer-ip is required, e.g. clele-print-daemon status --printer-ip 192.168.1.50")
	}

	status, err := ipp.GetPrinterStatus(ip)
	if err != nil {
		log.Fatalf("could not read status from %s: %v", ip, err)
	}

	fmt.Printf("Printer:   %s\n", ip)
	fmt.Printf("State:     %s\n", status.State)
	fmt.Printf("Media:     %s\n", status.Media)
	if status.Media != nil {
		fmt.Printf("           (IPP name: %s)\n", status.Media.Name)
	}
	fmt.Printf("Accepting: %v\n", status.AcceptingJobs)
	if problem := status.Problem(); problem != "" {
		fmt.Printf("Problem:   %s\n", problem)
		os.Exit(1)
	}
	fmt.Printf("Problem:   none\n")
}

func printJob(client *apiclient.Client, job *apiclient.Job) error {
	if job.PrinterIP == "" {
		err := client.Complete(job.JobID, "FAILED", "no printer IP configured for this daemon")
		if err != nil {
			log.Printf("failed to report job %d: %v", job.JobID, err)
		}
		return fmt.Errorf("no printer IP configured")
	}

	png, err := base64.StdEncoding.DecodeString(job.LabelPngBase64)
	if err != nil {
		client.Complete(job.JobID, "FAILED", "invalid label image")
		return fmt.Errorf("decode label png: %w", err)
	}

	// Print checks the printer over IPP first, so failures come back as the printer's own reason
	// ("cover open", "media empty", …) rather than succeeding silently on a write-only port.
	media, err := qlraster.Print(job.PrinterIP, png)
	if err != nil {
		client.Complete(job.JobID, "FAILED", err.Error())
		return err
	}

	log.Printf("printed job %d on %s (%s)", job.JobID, job.PrinterIP, media)
	return client.Complete(job.JobID, "DONE", "")
}
