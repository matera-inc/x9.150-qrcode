// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package metrics

import (
	"os"
	"runtime"
	"runtime/metrics"
	"syscall"
	"time"
)

// processStart is when this package was initialised, a few milliseconds after the process
// started: the reference of process_start_time_seconds and process_uptime_seconds.
var processStart = time.Now() //nolint:forbidigo // the process's real start, not business time

// Process registers the process gauges the Java build exported under the same names (S14.1-AC2):
// process_start_time_seconds, process_uptime_seconds, process_cpu_time_ns_total,
// process_files_open_files, process_files_max_files and system_cpu_count. Each is read when the
// page is scraped; one the operating system cannot give (no /proc or /dev/fd) is left out.
func (r *Registry) Process() {
	r.Collect(func(e *Exposition) {
		now := time.Now() //nolint:forbidigo // uptime is real time, not business time
		e.Gauge("process_start_time_seconds", "Start time of the process since unix epoch.",
			float64(processStart.UnixMilli())/1e3)
		e.Gauge("process_uptime_seconds", "The uptime of the process.", now.Sub(processStart).Seconds())
		var usage syscall.Rusage
		if syscall.Getrusage(syscall.RUSAGE_SELF, &usage) == nil {
			ns := usage.Utime.Nano() + usage.Stime.Nano()
			e.Family("process_cpu_time_ns_total", "The CPU time used by the process (user and system), in nanoseconds.",
				TypeCounter, Sample{Value: float64(ns)})
		}
		if n, ok := openFiles(); ok {
			e.Gauge("process_files_open_files", "The open file descriptor count.", float64(n))
		}
		var limit syscall.Rlimit
		if syscall.Getrlimit(syscall.RLIMIT_NOFILE, &limit) == nil {
			e.Gauge("process_files_max_files", "The maximum file descriptor count.", float64(limit.Cur))
		}
		e.Gauge("system_cpu_count", "The number of processors the Go scheduler runs on (GOMAXPROCS, which follows the container's CPU limit).",
			float64(runtime.GOMAXPROCS(0)))
	})
}

// openFiles counts the process's open file descriptors, the one used to count them included.
func openFiles() (int, bool) {
	for _, dir := range []string{"/proc/self/fd", "/dev/fd"} {
		if entries, err := os.ReadDir(dir); err == nil {
			return len(entries), true
		}
	}
	return 0, false
}

// The Go runtime's gauges, under the names prometheus/client_golang gives them, so a stock Go
// dashboard reads them. They replace the Java build's jvm_* families (docs/METRICS.md).
var runtimeMetrics = []struct {
	source, name, help, typ string
}{
	{"/sched/goroutines:goroutines", "go_goroutines", "Number of goroutines that currently exist.", TypeGauge},
	{"/sched/threads/total:threads", "go_threads", "Number of OS threads the Go runtime has created and not yet destroyed.", TypeGauge},
	{"/sched/gomaxprocs:threads", "go_sched_gomaxprocs_threads", "The current runtime.GOMAXPROCS setting.", TypeGauge},
	{"/gc/gomemlimit:bytes", "go_gc_gomemlimit_bytes", "The Go runtime's memory limit (GOMEMLIMIT).", TypeGauge},
	{"/memory/classes/heap/objects:bytes", "go_memstats_alloc_bytes", "Bytes of allocated heap objects.", TypeGauge},
	{"/gc/heap/allocs:bytes", "go_memstats_alloc_bytes_total", "Bytes allocated on the heap since the process started.", TypeCounter},
	{"/memory/classes/total:bytes", "go_memstats_sys_bytes", "Bytes of memory the Go runtime has obtained from the operating system.", TypeGauge},
	{"/gc/cycles/total:gc-cycles", "go_gc_cycles_total", "Garbage collection cycles completed since the process started.", TypeCounter},
}

// GoRuntime registers go_info and the Go runtime gauges of runtimeMetrics, read from
// runtime/metrics (no stop-the-world) when the page is scraped.
func (r *Registry) GoRuntime() {
	r.Collect(func(e *Exposition) {
		e.Gauge("go_info", "Information about the Go environment.", 1, Label{"version", runtime.Version()})
		samples := make([]metrics.Sample, len(runtimeMetrics))
		for i, m := range runtimeMetrics {
			samples[i].Name = m.source
		}
		metrics.Read(samples)
		for i, m := range runtimeMetrics {
			if samples[i].Value.Kind() != metrics.KindUint64 {
				continue // not supported by this Go version
			}
			e.Family(m.name, m.help, m.typ, Sample{Value: float64(samples[i].Value.Uint64())})
		}
	})
}
