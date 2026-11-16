// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package database

import (
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/metrics"
)

// Collect writes both pools' gauges and counters to the metrics page, labelled pool="write" and
// pool="read". They replace the Java build's mongodb_driver_pool_* families (docs/METRICS.md).
func (p *Pools) Collect(e *metrics.Exposition) {
	write, read := p.Write.Stat(), p.Read.Stat()
	w := []metrics.Label{{Name: "pool", Value: "write"}}
	r := []metrics.Label{{Name: "pool", Value: "read"}}
	family := func(name, help, typ string, value func(s *pgxpool.Stat) float64) {
		e.Family(name, help, typ,
			metrics.Sample{Labels: w, Value: value(write)}, metrics.Sample{Labels: r, Value: value(read)})
	}
	family("x9_database_pool_connections", "Connections open in the pool, idle or in use.", metrics.TypeGauge,
		func(s *pgxpool.Stat) float64 { return float64(s.TotalConns()) })
	family("x9_database_pool_acquired_connections", "Connections of the pool in use now.", metrics.TypeGauge,
		func(s *pgxpool.Stat) float64 { return float64(s.AcquiredConns()) })
	family("x9_database_pool_max_connections", "The most connections the pool opens.", metrics.TypeGauge,
		func(s *pgxpool.Stat) float64 { return float64(s.MaxConns()) })
	family("x9_database_pool_empty_acquire_total",
		"Acquisitions that had to wait for a connection because none was idle.", metrics.TypeCounter,
		func(s *pgxpool.Stat) float64 { return float64(s.EmptyAcquireCount()) })
	family("x9_database_pool_canceled_acquire_total",
		"Acquisitions cancelled before a connection was available.", metrics.TypeCounter,
		func(s *pgxpool.Stat) float64 { return float64(s.CanceledAcquireCount()) })
}
