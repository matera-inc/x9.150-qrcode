// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package health

import (
	"encoding/json"
	"net/http"
)

// InfoPath is the actuator's info endpoint, the third ADR-0031 keeps.
const InfoPath = "/actuator/info"

// Name is the service's name in the info answer.
const Name = "x9-qrcode"

type commitInfo struct {
	ID string `json:"id"`
}

type gitInfo struct {
	Commit commitInfo `json:"commit"`
}

type buildInfo struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

type info struct {
	Build buildInfo `json:"build"`
	Git   gitInfo   `json:"git"`
}

// RegisterInfo serves GET /actuator/info (S14.1-AC3): the build's name and version, and the commit
// it was built from, in the shape of Spring Boot's build and git info contributors:
//
//	{"build":{"name":"x9-qrcode","version":"3.0.0"},"git":{"commit":{"id":"<commit>"}}}
//
// version and commit are what the binary was stamped with (-ldflags). Nothing else is shown: no
// configuration, no host name, no secret. The Java build answered {}: Spring Boot shows the info.*
// properties only when its env contributor is enabled, and the Java build never enabled it.
func RegisterInfo(mux *http.ServeMux, version, commit string) {
	body, err := json.Marshal(info{Build: buildInfo{Name: Name, Version: version}, Git: gitInfo{commitInfo{ID: commit}}})
	if err != nil {
		panic("health: the info answer does not marshal: " + err.Error()) // two strings cannot fail
	}
	mux.HandleFunc("GET "+InfoPath, func(w http.ResponseWriter, r *http.Request) {
		write(w, r, http.StatusOK, string(body))
	})
}
