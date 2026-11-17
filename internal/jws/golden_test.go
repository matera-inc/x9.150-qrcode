// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

// The golden set Java exported (Story 4.7), read where it lives.
const (
	goldenDir   = "../../others/blackbox/golden"
	blackboxDir = "../../others/blackbox"
)

type goldenFile struct {
	ID         string         `json:"id"`
	File       string         `json:"file"`
	Kind       string         `json:"kind"`
	Signer     string         `json:"signer"`
	ProducedBy string         `json:"producedBy"`
	HTTPStatus int            `json:"httpStatus"`
	Header     map[string]any `json:"header"`
}

// javaTokens are the tokens Java signed and nobody touched, by the trusted rsa (PS512) and ec
// (ES256) signers.
func javaTokens(t *testing.T) []goldenFile {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(goldenDir, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		JWS []goldenFile `json:"jws"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	var out []goldenFile
	for _, f := range manifest.JWS {
		if f.ProducedBy == "java" && (f.Signer == "rsa" || f.Signer == "ec") {
			out = append(out, f)
		}
	}
	if len(out) < 8 {
		t.Fatalf("only %d Java-signed golden tokens", len(out))
	}
	return out
}

// jsonType names a header value's JSON type, telling an integer from any other number.
func jsonType(v any) string {
	switch x := v.(type) {
	case string:
		return "string"
	case json.Number:
		if _, ok := integer(x); ok {
			return "integer"
		}
		return "fraction"
	case []any:
		types := make([]string, 0, len(x))
		for _, e := range x {
			types = append(types, jsonType(e))
		}
		slices.Sort(types)
		return "array of " + strings.Join(slices.Compact(types), ",")
	case map[string]any:
		return "object"
	case bool:
		return "boolean"
	case nil:
		return "null"
	default:
		return fmt.Sprintf("%T", v)
	}
}

func sortedKeys(m map[string]any) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func critSet(v any) []string {
	list, _ := v.([]any)
	out := make([]string, 0, len(list))
	for _, e := range list {
		s, _ := e.(string)
		out = append(out, s)
	}
	sort.Strings(out)
	return out
}

// goTwin signs, with Go, the token of the same kind as a golden one: the same algorithm, the chain
// in x5c as the export publishes it, the same ttl, statusCode where Java put one, and Java's iat as
// the business clock's now.
func goTwin(t *testing.T, g goldenFile, golden decoded) (string, testKey, decoded) {
	t.Helper()
	alg, _ := golden.header["alg"].(string)
	iat, ok := integer(golden.header["iat"])
	if !ok {
		t.Fatalf("%s: golden iat %v is not an integer", g.ID, golden.header["iat"])
	}
	ttl, _ := integer(golden.header["ttl"])
	key := keyForAlg(t, alg)
	s := newSigner(t, Config{Algorithm: alg, Publication: PublishChain}, fixedClock{time.UnixMilli(iat)})
	id := uuid.MustParse(golden.header["correlationId"].(string))
	var token string
	var err error
	status, hasStatus := integer(golden.header["statusCode"])
	switch {
	case g.Kind == "notification-answer":
		token, err = s.SignAnswer(t.Context(), key, id, int(status))
	case hasStatus:
		token, err = s.Sign(t.Context(), key, Message{Payload: golden.payload, CorrelationID: id, TTL: time.Duration(ttl) * time.Millisecond, StatusCode: int(status)})
	default:
		token, err = s.Sign(t.Context(), key, Message{Payload: golden.payload, CorrelationID: id, TTL: time.Duration(ttl) * time.Millisecond})
	}
	if err != nil {
		t.Fatal(err)
	}
	return token, key, decode(t, token)
}

// S8.4-AC3: for every token Java signed (payload requests and responses, pre- and post-commit
// notifications, the signed answers), Go's token of the same kind has the same header field set,
// the same JSON type for every field (iat, ttl and statusCode integers), the same units (iat is the
// clock in ms, ttl in ms), the same crit set, the same alg, kid and typ, and an answer's body
// carries the same statusCode.
func TestHeaderMatchesJavasGoldenTokens(t *testing.T) {
	for _, g := range javaTokens(t) {
		t.Run(g.ID, func(t *testing.T) {
			raw, err := os.ReadFile(filepath.Join(goldenDir, g.File))
			if err != nil {
				t.Fatal(err)
			}
			java := decode(t, string(raw))
			_, _, got := goTwin(t, g, java)

			if want, have := sortedKeys(java.header), sortedKeys(got.header); !slices.Equal(want, have) {
				t.Fatalf("header fields: Java %v, Go %v", want, have)
			}
			for name, v := range java.header {
				if want, have := jsonType(v), jsonType(got.header[name]); want != have {
					t.Errorf("%s: Java writes %s, Go %s", name, want, have)
				}
			}
			for _, name := range []string{"alg", "kid", "typ", "correlationId", "iat", "ttl", "statusCode"} {
				if fmt.Sprint(java.header[name]) != fmt.Sprint(got.header[name]) {
					t.Errorf("%s: Java %v, Go %v", name, java.header[name], got.header[name])
				}
			}
			if want, have := critSet(java.header["crit"]), critSet(got.header["crit"]); !slices.Equal(want, have) {
				t.Errorf("crit: Java %v, Go %v", want, have)
			}
			if g.Kind == "notification-answer" {
				var jb, gb map[string]any
				if err := json.Unmarshal(java.payload, &jb); err != nil {
					t.Fatal(err)
				}
				if err := json.Unmarshal(got.payload, &gb); err != nil {
					t.Fatal(err)
				}
				if jb["statusCode"] != gb["statusCode"] || jb["statusCode"] != float64(g.HTTPStatus) {
					t.Errorf("answer body statusCode: Java %v, Go %v, HTTP %d", jb["statusCode"], gb["statusCode"], g.HTTPStatus)
				}
			}
		})
	}
}

// verifierCase is one token for the suite's independent verifier (testdata/x9client_verify.py).
type verifierCase struct {
	Name          string `json:"name"`
	Token         string `json:"token"`
	Certificate   string `json:"certificate"`
	NowMillis     int64  `json:"nowMillis"`
	CorrelationID string `json:"correlationId"`
	StatusCode    *int   `json:"statusCode"`
	Answer        bool   `json:"answer"`
	Expect        string `json:"expect"`
}

// S8.4-AC3: the black-box suite's independent verifier (others/blackbox/x9client/jws.py, which
// shares no code with Go or Java) accepts every token Go signs: every algorithm and publication,
// the Go twin of every Java golden token, and the signed answers.
//
// The tokens are written to a temporary file and checked by one Python run. The suite's
// interpreter is .blackbox-venv (make blackbox-venv), or X9_BLACKBOX_PYTHON; without one the test
// is skipped locally and fails in CI, where the go job creates the venv.
func TestIndependentVerifierAcceptsGoTokens(t *testing.T) {
	python := blackboxPython(t)

	cases := make([]verifierCase, 0, 128)
	add := func(name, token string, key testKey, at time.Time, id uuid.UUID, status int, answer bool) {
		c := verifierCase{
			Name: name, Token: token, Certificate: base64.StdEncoding.EncodeToString(key.chain[0].Raw),
			NowMillis: at.Add(time.Second).UnixMilli(), CorrelationID: id.String(), Answer: answer, Expect: "VERIFIED",
		}
		if status != 0 {
			c.StatusCode = &status
		}
		cases = append(cases, c)
	}
	for _, alg := range []string{"RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"} {
		for _, pub := range []Publication{PublishJWKSet, PublishPEM, PublishChain} {
			key := keyForAlg(t, alg)
			s := newSigner(t, Config{Algorithm: alg, Publication: pub}, fixedClock{epoch})
			id := NewCorrelationID()
			token, err := s.Sign(t.Context(), key, Message{Payload: []byte(`{"qrCodeContent":"MDA="}`), CorrelationID: id})
			if err != nil {
				t.Fatal(err)
			}
			add(alg+"-"+string(pub)+"-question", token, key, epoch, id, 0, false)
			token, err = s.SignAnswer(t.Context(), key, id, 409)
			if err != nil {
				t.Fatal(err)
			}
			add(alg+"-"+string(pub)+"-answer-409", token, key, epoch, id, 409, true)
		}
	}
	for _, g := range javaTokens(t) {
		raw, err := os.ReadFile(filepath.Join(goldenDir, g.File))
		if err != nil {
			t.Fatal(err)
		}
		java := decode(t, string(raw))
		token, key, got := goTwin(t, g, java)
		iat, _ := integer(got.header["iat"])
		status, _ := integer(got.header["statusCode"])
		add("go-twin-of-"+g.ID, token, key, time.UnixMilli(iat), uuid.MustParse(got.header["correlationId"].(string)),
			int(status), g.Kind == "notification-answer")
	}

	// Controls the verifier must refuse, so that a verifier accepting everything fails this test: a
	// Go token with its payload changed, one judged at iat + ttl, one under another key.
	verified := len(cases)
	if verified == 0 {
		t.Fatal("no token to verify")
	}
	control := cases[0]
	parts := strings.Split(control.Token, ".")
	tampered, wrongKey := control, control
	tampered.Name, tampered.Expect = "control-altered-payload", "REFUSED"
	tampered.Token = parts[0] + "." + base64.RawURLEncoding.EncodeToString([]byte(`{"qrCodeContent":"MDE="}`)) + "." + parts[2]
	expired := control
	expired.Name, expired.Expect, expired.NowMillis = "control-expired", "REFUSED", epoch.Add(DefaultTTL).UnixMilli()
	wrongKey.Name, wrongKey.Expect = "control-another-certificate", "REFUSED"
	wrongKey.Certificate = base64.StdEncoding.EncodeToString(keyFor(t, "P-256").chain[0].Raw)
	cases = append(cases, tampered, expired, wrongKey)

	data, err := json.Marshal(cases)
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(t.TempDir(), "go-tokens.json")
	if err := os.WriteFile(file, data, 0o600); err != nil {
		t.Fatal(err)
	}
	script, err := filepath.Abs("testdata/x9client_verify.py")
	if err != nil {
		t.Fatal(err)
	}
	dir, err := filepath.Abs(blackboxDir)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Minute)
	defer cancel()
	//nolint:gosec // G204: the interpreter is the suite's venv or the developer's choice, the arguments are this test's files
	out, err := exec.CommandContext(ctx, python, "-I", script, dir, file).CombinedOutput()
	if err != nil {
		t.Fatalf("x9client refused Go's tokens (%v):\n%s", err, out)
	}
	if want := fmt.Sprintf("%d/%d as expected", len(cases), len(cases)); !strings.Contains(string(out), want) {
		t.Fatalf("x9client did not judge all %d tokens as expected:\n%s", len(cases), out)
	}
	t.Logf("x9client: %d Go tokens verified, %d controls refused", verified, len(cases)-verified)
}

// blackboxPython is the suite's interpreter, with cryptography installed.
func blackboxPython(t *testing.T) string {
	t.Helper()
	//nolint:forbidigo // a test's choice of interpreter, not the service's configuration
	if p, ok := os.LookupEnv("X9_BLACKBOX_PYTHON"); ok && p != "" {
		return p
	}
	venv, err := filepath.Abs("../../.blackbox-venv/bin/python")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(venv); err == nil {
		return venv
	}
	//nolint:forbidigo // CI is where a skipped AC check must fail instead
	if _, ci := os.LookupEnv("CI"); ci {
		t.Fatal("no .blackbox-venv: the go job must run `make blackbox-venv` before the tests (S8.4-AC3)")
	}
	t.Skip("no .blackbox-venv (make blackbox-venv) and no X9_BLACKBOX_PYTHON: the independent verifier is not run")
	return ""
}
