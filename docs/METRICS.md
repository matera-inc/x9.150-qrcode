# Metrics and actuator endpoints

What the service serves at `/actuator/prometheus` and `/actuator/info`, and what became of every
metric family and actuator endpoint the Java build exposed until it was retired at 3.0.0 (Story
14.1, [ADR-0039](adr/0039-java-retired-at-3-0-0.md)). If you have dashboards or alerts written for
the Java build, this page tells you which keep working and what to change.

Both endpoints answer on the API port (`8080`), as `/actuator/health` does
([ADR-0031](adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md): only health,
prometheus and info are exposed). They are unauthenticated, like the rest of the API: keep them
off the public edge.

## How the inventory was made

The Java image was built from this repository before 3.0.0 (the `java-final` tag), started
with a MongoDB 7.0 replica set, and given a short load covering every kind of route: creates (201
and 400), a GET (200, 404, 400), a PATCH, a status update, a payload fetch, a payment notification,
the event stream, the JWK set and a certificate, `signature/generate`, an unknown path (404), a
wrong method (405) and an `OPTIONS`. `/actuator/prometheus` then listed **61 metric families**.
Some appear only after their first event (the GC's concurrent phase, for one), so a Java build
that ran longer may show one or two more of the kinds listed under [Dropped](#dropped).

| | Families |
|---|---|
| [Kept](#kept), same name, type and labels | 9 |
| [Replaced](#replaced), by a Go family | 12 |
| [Dropped](#dropped) | 40 |
| **The Java build's total** | **61** |

The Go build adds its own families: [below](#the-go-builds-own).

## Standard library, not `prometheus/client_golang`

`internal/metrics` writes the Prometheus text format (version 0.0.4) by hand, as it has since the
retention counters (Epic 12), and stays that way. The series are bounded (route templates ×
methods × statuses, two pools, a fixed set of gauges); a counter is an atomic integer, a gauge is
read when the page is scraped, and the page is a few kilobytes. `prometheus/client_golang` would
add a module tree to a binary with a handful of direct dependencies, and a second registry model,
for no metric this service needs. What it would have given for free (the request timer, the
process and Go runtime collectors, the log counter) is about 350 lines here, each tested. It
becomes the right choice the day the service needs histograms with exemplars, the OpenMetrics or
protobuf formats, or a push gateway.

## Kept

Same name, same type, same label names. A dashboard or an alert written against the Java build
reads these unchanged. The test `X9-OPS-001` reads this table and fails if the Go build does not
serve every family in it, with its type and labels.

<!-- kept:start -->
| Family | Type | Labels | What it measures on Go |
|---|---|---|---|
| `http_server_requests_seconds` | summary | `error`, `exception`, `method`, `outcome`, `status`, `uri` | Every request answered, `_count` and `_sum` in seconds. `uri` is the route's template (`/api/v1/payment-request/{id}`), never the raw path; a request no route takes is `/**` when it is a 404 and `UNKNOWN` otherwise (405, `OPTIONS`), as Spring labelled them. `outcome` is Spring's (`SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR`, …). `error` and `exception` are always `none`: Go has no exception class to name (Java named one only for an exception no handler caught). |
| `http_server_requests_seconds_max` | gauge | `error`, `exception`, `method`, `outcome`, `status`, `uri` | The longest request of the last two minutes, per series: Micrometer's time window (three buffers rotated every 40 seconds). |
| `process_start_time_seconds` | gauge | — | When the process started, in seconds since the epoch. |
| `process_uptime_seconds` | gauge | — | Seconds since the process started. |
| `process_cpu_time_ns_total` | counter | — | CPU time the process used, user and system, in nanoseconds (`getrusage`). |
| `process_files_open_files` | gauge | — | Open file descriptors (`/proc/self/fd`). |
| `process_files_max_files` | gauge | — | The open-file limit (`RLIMIT_NOFILE`, soft). |
| `system_cpu_count` | gauge | — | Processors the service runs on: `GOMAXPROCS`, which follows the container's CPU limit, as the JVM's count did. |
| `logback_events_total` | counter | `level` | Log lines written, by level (`debug`, `info`, `warn`, `error`; `trace` is always 0, Go has no trace level). The name is the Java build's, kept so an alert on the rate of errors keeps working. |
<!-- kept:end -->

In both builds a scrape of `/actuator/prometheus` is itself counted, under
`uri="/actuator/prometheus"`, as it was. The 400 the Java build's Tomcat gave a request whose
headers pass 30 KB was not counted (Spring never saw it); the Go build counts it under `UNKNOWN`.

## Replaced

The Java family has no meaning in Go, but the question it answered still has an answer. Change
the query to the Go family.

| Java family | Go family | Notes |
|---|---|---|
| `jvm_info` | `go_info{version}` | The runtime's version. |
| `jvm_memory_used_bytes{area,id}` | `go_memstats_alloc_bytes` | Heap in use. Go has one heap, so no `area` or `id`. |
| `jvm_memory_committed_bytes{area,id}` | `go_memstats_sys_bytes` | Memory the runtime holds from the operating system. |
| `jvm_memory_max_bytes{area,id}` | `go_gc_gomemlimit_bytes` | The soft limit the collector works to (`GOMEMLIMIT`). |
| `jvm_gc_memory_allocated_bytes_total` | `go_memstats_alloc_bytes_total` | Bytes allocated since start. |
| `jvm_gc_pause_seconds{action,cause,gc}` | `go_gc_cycles_total` | Collections completed. Go's collector is concurrent: its stop-the-world pauses are well under a millisecond, so their duration is not exported. |
| `jvm_threads_live_threads` | `go_threads` | Operating-system threads. The unit of concurrency is the goroutine: `go_goroutines`. |
| `process_cpu_usage` | `rate(process_cpu_time_ns_total[1m]) / 1e9` | The share of one CPU the process used. Divide by `system_cpu_count` for Java's 0–1 scale. |
| `mongodb_driver_pool_size{cluster_id,server_address}` | `x9_database_pool_connections{pool}` | Connections open, idle or in use. `pool` is `write` or `read`. |
| `mongodb_driver_pool_checkedout{cluster_id,server_address}` | `x9_database_pool_acquired_connections{pool}` | Connections in use now. |
| `mongodb_driver_pool_waitqueuesize{cluster_id,server_address}` | `x9_database_pool_empty_acquire_total{pool}` | **Type changes**, gauge to counter: pgx does not report how many are waiting now, but counts the acquisitions that had to wait. Alert on its rate. |
| `mongodb_driver_pool_checkoutfailed_total{cluster_id,server_address}` | `x9_database_pool_canceled_acquire_total{pool}` | Acquisitions given up before a connection came (the request's deadline passed). |

## Dropped

No Go equivalent, or nothing an operator of this service would watch. Grouped by prefix.

| Java families | Why |
|---|---|
| `application_ready_time_seconds`, `application_started_time_seconds` | Spring's start-up timers. The Go build is ready in well under a second; the `x9-qrcode ready` log line marks the moment. |
| `disk_free_bytes`, `disk_total_bytes` | Spring's disk-space check of its working directory. The Go build writes no file (logs go to standard output); watch the node's disk with the node exporter. |
| `http_server_requests_active_seconds`, `http_server_requests_active_seconds_max` | Spring's long-task timer of requests in flight. Its labels were meaningless in the Java build (`uri` always `UNKNOWN`, `status` always `200`). The concurrency is `rate(http_server_requests_seconds_sum[1m])`. |
| `jvm_buffer_count_buffers`, `jvm_buffer_memory_used_bytes`, `jvm_buffer_total_capacity_bytes` | The JVM's direct and mapped buffers. |
| `jvm_classes_loaded_classes`, `jvm_classes_unloaded_classes_total`, `jvm_compilation_time_ms_total` | Class loading and the JIT compiler. A Go binary is compiled ahead of time. |
| `jvm_gc_concurrent_phase_time_seconds`, `jvm_gc_concurrent_phase_time_seconds_max`, `jvm_gc_live_data_size_bytes`, `jvm_gc_max_data_size_bytes`, `jvm_gc_memory_promoted_bytes_total`, `jvm_gc_overhead`, `jvm_gc_pause_seconds_max`, `jvm_memory_usage_after_gc` | G1's generations, phases and pauses. Go's collector has no generations; see `go_gc_cycles_total` and `go_memstats_*` above. |
| `jvm_threads_daemon_threads`, `jvm_threads_peak_threads`, `jvm_threads_started_threads_total`, `jvm_threads_states_threads` | Java thread states. See `go_threads` and `go_goroutines`. |
| `mongodb_driver_commands_seconds`, `mongodb_driver_commands_seconds_max` | The MongoDB driver's per-command timer. The Go build does not time each SQL statement; `http_server_requests_seconds` times every request, and PostgreSQL's own `pg_stat_statements` times the statements, for the DBA. |
| `spring_data_repository_invocations_seconds`, `spring_data_repository_invocations_seconds_max` | Spring Data's repository timer. There is no repository layer; see the previous row. |
| `system_cpu_usage`, `system_load_average_1m` | The host's CPU and load. Watch the node with the node exporter or the kubelet's cAdvisor metrics. |
| `tasks_scheduled_execution_seconds`, `tasks_scheduled_execution_seconds_max`, `tasks_scheduled_execution_active_seconds`, `tasks_scheduled_execution_active_seconds_max` | Spring's timer of `@Scheduled` methods: the Java build's event drain, which the Go build does not have (an event is written in the transition's own transaction, [ADR-0025](adr/0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)). The one scheduled job of the Go build, retention, has its own counters (below). |
| `tomcat_sessions_active_current_sessions`, `tomcat_sessions_active_max_sessions`, `tomcat_sessions_alive_max_seconds`, `tomcat_sessions_created_sessions_total`, `tomcat_sessions_expired_sessions_total`, `tomcat_sessions_rejected_sessions_total` | Servlet sessions. Neither build ever created one; they were always 0. |

## The Go build's own

| Family | Type | Labels | What it measures |
|---|---|---|---|
| `x9_retention_deleted_rows_total` | counter | `table` | Rows the retention job deleted, per table ([ADR-0029](adr/0029-time-is-applied-on-read-and-retention-is-the-only-job.md)). |
| `x9_retention_runs_total` | counter | — | Retention runs, finished or failed. |
| `x9_retention_runs_failed_total` | counter | — | Retention runs that failed; each is logged and retried at the next tick. |
| `x9_database_pool_connections` | gauge | `pool` | Connections open in the pool, idle or in use. |
| `x9_database_pool_acquired_connections` | gauge | `pool` | Connections in use now. |
| `x9_database_pool_max_connections` | gauge | `pool` | The pool's size (`database.pool-writes`, `database.pool-reads`). |
| `x9_database_pool_empty_acquire_total` | counter | `pool` | Acquisitions that waited because no connection was idle. |
| `x9_database_pool_canceled_acquire_total` | counter | `pool` | Acquisitions given up before a connection came. |
| `go_info` | gauge | `version` | The Go version the binary was built with. |
| `go_goroutines` | gauge | — | Goroutines that exist now. |
| `go_threads` | gauge | — | Operating-system threads. |
| `go_sched_gomaxprocs_threads` | gauge | — | `GOMAXPROCS`. |
| `go_gc_gomemlimit_bytes` | gauge | — | `GOMEMLIMIT`. |
| `go_memstats_alloc_bytes` | gauge | — | Heap in use. |
| `go_memstats_alloc_bytes_total` | counter | — | Bytes allocated since start. |
| `go_memstats_sys_bytes` | gauge | — | Memory held from the operating system. |
| `go_gc_cycles_total` | counter | — | Garbage collections completed. |

The `go_*` names are the ones `prometheus/client_golang` gives these values, so a stock Go
dashboard reads them; they come from `runtime/metrics`, which reads without stopping the world.

## `/actuator/info`

```json
{"build":{"name":"x9-qrcode","version":"3.0.0"},"git":{"commit":{"id":"<commit>"}}}
```

The version and the commit the binary was stamped with (`-ldflags`; `docker build --build-arg
VERSION=… --build-arg REVISION=…`), the same two values `x9-qrcode -version` prints and the
`x9-qrcode starting` log line carries. The shape is Spring Boot's build and git info
contributors'. Nothing else is shown: no configuration, no host name, no secret.

The Java build's `/actuator/info` answered `{}`. Its `application.yml` set `info.app.*` and
`info.tags.pod`, but Spring Boot shows those only when its `env` info contributor is turned on
(`management.info.env.enabled`), and the Java build never turned it on. Nothing that read the Java
answer can depend on a member, so the Go answer adds them.

## The other actuator endpoints

The Java build exposed every actuator endpoint (`management.endpoints.web.exposure.include: "*"`,
less `shutdown` and `heapdump`). The Go build serves only `/actuator/health` (with `liveness` and
`readiness`), `/actuator/prometheus` and `/actuator/info`. These answer 404:

| Java endpoint | Why it is gone |
|---|---|
| `/actuator` (the index of links) | Three fixed paths need no index. |
| `/actuator/beans`, `/actuator/conditions`, `/actuator/configprops`, `/actuator/env`, `/actuator/mappings`, `/actuator/features` | Spring's application context. The configuration is one YAML file and the environment ([CONFIGURATION-FROM-JAVA.md](CONFIGURATION-FROM-JAVA.md)); the routes are [ENDPOINTS.md](../ENDPOINTS.md). |
| `/actuator/caches` | No cache. |
| `/actuator/loggers` | One level for the whole service, `X9_LOG_LEVEL`, set at start. |
| `/actuator/logfile` | No log file: standard output only. |
| `/actuator/metrics` | Micrometer's JSON view of the same metrics; `/actuator/prometheus` has them. |
| `/actuator/scheduledtasks` | Spring's `@Scheduled` list. The one job, retention, logs each run. |
| `/actuator/threaddump` | A JVM thread dump. A Go build exposes no profiling endpoint in production. |
| `/actuator/refresh` | Spring Cloud's configuration reload. The configuration is read once, at start. |
