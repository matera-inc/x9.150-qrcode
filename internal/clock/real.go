// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package clock

import (
	"context"
	"time"
)

// Sleep waits a real duration d, or until ctx ends, whichever comes first, and returns ctx's error
// in the second case. It is for periodic work and retry delays (a keystore reload, a back-off),
// which run on real time: moving the business clock's offset neither hastens nor delays it.
func Sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
