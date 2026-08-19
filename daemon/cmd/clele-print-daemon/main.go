// clele-print-daemon prints labels pushed from the Clele web app to a label printer, without any
// user interaction. Two printer families are supported: a network Brother QL (raster over raw TCP,
// status over IPP) and a USB Dymo LabelWriter (everything through the local CUPS queue over IPP).
// See daemon/README.md for install/usage.
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
	"github.com/clele/print-daemon/internal/cupsprint"
	"github.com/clele/print-daemon/internal/ipp"
	"github.com/clele/print-daemon/internal/printer"
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

	// Which printer to drive is configured in the web app, not here, and is returned with every
	// poll. The probe result is cached and reported back so the app can size labels to the
	// printable area of whatever is actually loaded.
	var (
		target   printer.Target
		driver   printer.Driver
		report   *printer.Report
		probedAt time.Time
		// Capabilities describe the *machine*, not the printer currently configured, so they are
		// discovered independently of the target: the web app needs the queue list before the user
		// can pick a queue, and asking only after they have chosen a type would leave the picker
		// empty at exactly the moment they need it.
		capsReported bool
		capsTried    bool
	)
	const probeTTL = time.Minute

	for {
		if driver != nil && time.Since(probedAt) > probeTTL {
			probedAt = time.Now()
			status, probed, err := driver.Probe()
			if err != nil {
				log.Printf("could not read printer status: %v", err)
			} else {
				if report == nil || probed.Media == nil || report.Media == nil || *probed.Media != *report.Media {
					log.Printf("printer: %s (%s)", probed.Media, status.State)
				}
				report = probed
			}
		}

		poll, err := client.NextJob(25, report)

		// A changed target invalidates everything cached about the old printer; re-probe and
		// re-report at once rather than serving stale geometry to the next job.
		//
		// An empty target means the poll never reached the backend, so keep the configuration
		// already held rather than dropping the printer on every transient network blip.
		if !poll.Target.Empty() && poll.Target != target {
			target = poll.Target
			if target.Type == "" {
				target.Type = printer.TypeBrotherQL // backend predating printer types
			}
			driver = driverFor(target)
			report = nil
			probedAt = time.Time{}
		}

		// Retried only when the backend asks, so a machine with no CUPS at all logs its one
		// failure and then stays quiet rather than complaining on every poll forever.
		if !capsReported && (!capsTried || poll.WantCapabilities) {
			capsTried = true
			capsReported = reportCapabilities(client, cupsprint.New("", ""))
		}

		if err != nil {
			log.Printf("poll error: %v", err)
			continue
		}
		if poll.Job == nil {
			continue
		}
		if err := printJob(client, poll.Job, target); err != nil {
			log.Printf("job %d failed: %v", poll.Job.JobID, err)
		}
	}
}

// driverFor picks the implementation for a printer target. This is the only place that knows which
// families exist; everything else works through printer.Driver.
func driverFor(t printer.Target) printer.Driver {
	switch t.Type {
	case printer.TypeDymoCUPS:
		// Built even without a queue selected: discovering the machine's queues is exactly what
		// the web app needs before the user can pick one.
		return cupsprint.New(t.Queue, t.MediaKeyword)
	case printer.TypeBrotherQL:
		if t.IP == "" {
			return nil
		}
		return qlraster.NewDriver(t.IP)
	default:
		return nil
	}
}

// reportCapabilities pushes the queue and label-size lists found on this machine, and reports
// whether they may be considered sent. A driver with nothing to discover counts as sent, so it is
// not retried.
func reportCapabilities(client *apiclient.Client, driver printer.Driver) bool {
	caps, err := driver.Capabilities()
	if err != nil {
		log.Printf("could not read printer capabilities: %v", err)
		return false
	}
	if caps == nil {
		return true
	}
	if err := client.ReportCapabilities(caps); err != nil {
		log.Printf("could not report printer capabilities: %v", err)
		return false
	}
	log.Printf("reported %d print queue(s) to the backend", len(caps.Queues))
	return true
}

// cmdStatus queries a printer and prints what it reports — media loaded, error state, and for a
// CUPS printer the queues available. Diagnostic aid for "the printer shows an error but I can't
// tell why" and for "which queue name do I put in the web app".
func cmdStatus(args []string) {
	fs := flag.NewFlagSet("status", flag.ExitOnError)
	printerIP := fs.String("printer-ip", "", "printer IP address, for a network Brother QL")
	printerType := fs.String("printer-type", "", "BROTHER_QL or DYMO_CUPS (default: inferred from the other flags)")
	queue := fs.String("queue", "", "CUPS queue name, for a USB printer")
	media := fs.String("media", "", "IPP media keyword, for a printer that cannot sense its roll")
	fs.Parse(args)

	target := printer.Target{Type: *printerType, IP: *printerIP, Queue: *queue, MediaKeyword: *media}
	if target.Type == "" {
		target.Type = printer.TypeBrotherQL
		if *printerIP == "" {
			target.Type = printer.TypeDymoCUPS
		}
	}

	driver := driverFor(target)
	if driver == nil {
		log.Fatal("nothing to query: give --printer-ip for a Brother QL, or --queue for a CUPS printer")
	}

	// The queue list does not need a queue selected, so print it before probing — it is what the
	// user is looking for when they do not yet know the name to configure.
	if caps, err := driver.Capabilities(); err == nil && caps != nil {
		fmt.Println("Print queues on this machine:")
		for _, q := range caps.Queues {
			fmt.Printf("  %-24s %s (%d label sizes)\n", q.Name, q.MakeAndModel, len(q.Media))
		}
		fmt.Println()
	}
	if target.Type == printer.TypeDymoCUPS && target.Queue == "" {
		fmt.Println("No queue selected; pass --queue <name> to query one.")
		return
	}

	status, report, err := driver.Probe()
	if err != nil {
		log.Fatalf("could not read printer status: %v", err)
	}

	fmt.Printf("Printer:   %s\n", printerDescription(target))
	fmt.Printf("Model:     %s\n", status.MakeAndModel)
	fmt.Printf("State:     %s\n", status.State)
	fmt.Printf("Media:     %s\n", status.Media)
	if status.Media != nil {
		fmt.Printf("           (IPP name: %s)\n", status.Media.Name)
	}
	fmt.Printf("Printable: %s x %s mm\n", ipp.Mm(report.PrintableWidthMm), ipp.Mm(report.PrintableLengthMm))
	fmt.Printf("Accepting: %v\n", status.AcceptingJobs)
	if problem := status.Problem(); problem != "" {
		fmt.Printf("Problem:   %s\n", problem)
		os.Exit(1)
	}
	fmt.Printf("Problem:   none\n")
}

func printerDescription(t printer.Target) string {
	if t.Type == printer.TypeDymoCUPS {
		return "CUPS queue " + t.Queue
	}
	return t.IP
}

func printJob(client *apiclient.Client, job *apiclient.Job, fallback printer.Target) error {
	// Prefer the target carried on the job itself; fall back to the poll's for a backend that does
	// not send it yet.
	target := job.Target()
	if target.Type == "" {
		target = fallback
	}
	if !target.Configured() {
		if err := client.Complete(job.JobID, "FAILED", target.Missing()); err != nil {
			log.Printf("failed to report job %d: %v", job.JobID, err)
		}
		return fmt.Errorf("%s", target.Missing())
	}

	driver := driverFor(target)
	if driver == nil {
		msg := fmt.Sprintf("unsupported printer type %q", target.Type)
		client.Complete(job.JobID, "FAILED", msg)
		return fmt.Errorf("%s", msg)
	}

	png, err := base64.StdEncoding.DecodeString(job.LabelPngBase64)
	if err != nil {
		client.Complete(job.JobID, "FAILED", "invalid label image")
		return fmt.Errorf("decode label png: %w", err)
	}

	// Every driver checks the printer over IPP before printing, so failures come back as the
	// printer's own reason ("cover open", "media empty", …) rather than succeeding silently.
	if err := driver.Print(png); err != nil {
		client.Complete(job.JobID, "FAILED", err.Error())
		return err
	}

	log.Printf("printed job %d on %s", job.JobID, printerDescription(target))
	return client.Complete(job.JobID, "DONE", "")
}
