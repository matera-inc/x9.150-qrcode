// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package emv

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// The golden set Java exported (Story 4.7). Read from the repository, never copied.
const manifestPath = "../../others/blackbox/golden/manifest.json"

type goldenField struct {
	Tag    string `json:"tag"`
	Length int    `json:"length"`
	Value  string `json:"value"`
}

type goldenEMV struct {
	ID         string        `json:"id"`
	Host       string        `json:"host"`
	LocationID string        `json:"locationId"`
	QRCode     string        `json:"qrCode"`
	Length     int           `json:"length"`
	CRC        string        `json:"crc"`
	Fields     []goldenField `json:"fields"`
	Template26 []goldenField `json:"template26"`
	URL        string        `json:"url"`
	Request    struct {
		Creditor struct {
			Name    string `json:"name"`
			MCC     string `json:"MCC"`
			Address struct {
				City    string `json:"city"`
				Country string `json:"country"`
			} `json:"address"`
		} `json:"creditor"`
	} `json:"request"`
}

func golden(tb testing.TB) []goldenEMV {
	tb.Helper()
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		tb.Fatal(err)
	}
	var m struct {
		EMV []goldenEMV `json:"emv"`
	}
	if err := json.Unmarshal(data, &m); err != nil {
		tb.Fatal(err)
	}
	return m.EMV
}

func toGolden(fields []Field) []goldenField {
	out := make([]goldenField, 0, len(fields))
	for _, f := range fields {
		out = append(out, goldenField{Tag: f.Tag, Length: f.Len(), Value: f.Value})
	}
	return out
}

// S8.1-AC2: every golden string decodes to the manifest's fields, CRC, tag 26 and URL.
func TestGoldenStringsDecode(t *testing.T) {
	cases := golden(t)
	if len(cases) != 24 {
		t.Fatalf("the manifest has %d EMV strings, want 24", len(cases))
	}
	for _, g := range cases {
		t.Run(g.ID+"@"+g.Host, func(t *testing.T) {
			q, err := Decode(g.QRCode)
			if err != nil {
				t.Fatal(err)
			}
			if len(g.QRCode) != g.Length {
				t.Errorf("length %d, manifest %d", len(g.QRCode), g.Length)
			}
			if q.CRC != g.CRC {
				t.Errorf("CRC %s, manifest %s", q.CRC, g.CRC)
			}
			if diff := cmp.Diff(g.Fields, toGolden(q.Fields)); diff != "" {
				t.Errorf("fields (-manifest +decoded):\n%s", diff)
			}
			if diff := cmp.Diff(g.Template26, toGolden(q.Template)); diff != "" {
				t.Errorf("tag 26 (-manifest +decoded):\n%s", diff)
			}
			if q.URL != g.URL {
				t.Errorf("URL %q, manifest %q", q.URL, g.URL)
			}
		})
	}
}

// S8.1-AC2: every golden request encodes back to the identical string.
func TestGoldenRequestsEncode(t *testing.T) {
	for _, g := range golden(t) {
		t.Run(g.ID+"@"+g.Host, func(t *testing.T) {
			url := LocationURL(g.Host, g.LocationID)
			if url != g.URL {
				t.Fatalf("location URL %q, manifest %q", url, g.URL)
			}
			c := g.Request.Creditor
			got, err := Encode(url, Merchant{
				CategoryCode: c.MCC,
				Country:      c.Address.Country,
				Name:         c.Name,
				City:         c.Address.City,
			})
			if err != nil {
				t.Fatal(err)
			}
			if got != g.QRCode {
				t.Fatalf("encoded\n  %s\nmanifest\n  %s", got, g.QRCode)
			}
		})
	}
}

// S8.1-AC1: CRC16-CCITT with poly 0x1021 and init 0xFFFF (CRC-16/CCITT-FALSE).
func TestCRC16(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want uint16
	}{
		{"", 0xFFFF},
		{"123456789", 0x29B1}, // the catalogue check value of CRC-16/CCITT-FALSE
		{"00020101021226800006org.x90166x9-150.example.com/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5" +
			"520449005303840540105802US5911Blackbox Co6011Los Angeles6304", 0xAA0B},
	} {
		if got := CRC16([]byte(tc.in)); got != tc.want {
			t.Errorf("CRC16(%q) = %04X, want %04X", tc.in, got, tc.want)
		}
	}
}

// S8.1-AC1: TLV is written and read back; tag 26 carries GUI org.x9 then sub-tag 01.
func TestTLVRoundTrip(t *testing.T) {
	fields := []Field{{"00", GUI}, {"01", "x9.example/pub/api/v1/loc/0123456789ABCDEF0123456789ABCDEF"}}
	s, err := EncodeFields(fields)
	if err != nil {
		t.Fatal(err)
	}
	if want := "0006org.x90158x9.example/pub/api/v1/loc/0123456789ABCDEF0123456789ABCDEF"; s != want {
		t.Fatalf("encoded %q, want %q", s, want)
	}
	back, err := DecodeFields(s)
	if err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(fields, back); diff != "" {
		t.Fatalf("(-written +read):\n%s", diff)
	}
}

// Java leaves a blank value out, cuts the name to 25 and the city to 15, and writes 840 and 0.
func TestEncodeLikeJava(t *testing.T) {
	url := LocationURL("x9.example", strings.Repeat("A", LocationIDLength))
	got, err := Encode(url, Merchant{CategoryCode: "4900", Country: "US", Name: "   ", City: "A City Longer Than Fifteen"})
	if err != nil {
		t.Fatal(err)
	}
	q, err := Decode(got)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"00", "01", "26", "52", "53", "54", "58", "60", "63"}
	tags := make([]string, 0, len(q.Fields))
	for _, f := range q.Fields {
		tags = append(tags, f.Tag)
	}
	if diff := cmp.Diff(want, tags); diff != "" {
		t.Fatalf("tags (-want +got):\n%s", diff)
	}
	if city := value(q.Fields, TagMerchantCity); city != "A City Longer T" {
		t.Errorf("city %q, want it cut to 15", city)
	}
	if v := value(q.Fields, TagTransactionCurrency) + "/" + value(q.Fields, TagTransactionAmount); v != "840/0" {
		t.Errorf("currency/amount %q, want 840/0", v)
	}
}

// S8.1-AC4: the host limit is 37 because tag 26 holds at most 99 characters; a 37-character
// host fills it exactly, and Encode refuses a URL one character longer.
func TestHostLimitIs37(t *testing.T) {
	if MaxHostLength != 37 {
		t.Fatalf("MaxHostLength = %d, want 37", MaxHostLength)
	}
	host37 := strings.Repeat("a", 37-len(".example.com")) + ".example.com"
	if err := CheckHost(host37); err != nil {
		t.Fatalf("a 37-character host: %v", err)
	}
	m := Merchant{CategoryCode: "4900", Country: "US", Name: "N", City: "C"}
	s, err := Encode(LocationURL(host37, strings.Repeat("A", LocationIDLength)), m)
	if err != nil {
		t.Fatal(err)
	}
	q, err := Decode(s)
	if err != nil {
		t.Fatal(err)
	}
	if n := q.Fields[2].Len(); n != MaxValueLength {
		t.Fatalf("tag 26 is %d characters, want exactly %d", n, MaxValueLength)
	}

	host38 := "a" + host37
	err = CheckHost(host38)
	if err == nil || !strings.Contains(err.Error(), "37") {
		t.Fatalf("a 38-character host: err = %v, want the limit 37 named", err)
	}
	if _, err := Encode(LocationURL(host38, strings.Repeat("A", LocationIDLength)), m); !errors.Is(err, ErrValue) {
		t.Fatalf("a 38-character host encoded: err = %v, want ErrValue", err)
	}
	for _, bad := range []string{"x9 example", "x9.exämple", "x9.example\t"} {
		if err := CheckHost(bad); err == nil {
			t.Errorf("CheckHost(%q) accepted it", bad)
		}
	}
}

func TestEncodeRefusesWhatEMVCannotCarry(t *testing.T) {
	url := LocationURL("x9.example", strings.Repeat("A", LocationIDLength))
	for _, m := range []Merchant{
		{CategoryCode: "4900", Country: "US", Name: "Café", City: "C"},
		{CategoryCode: "4900", Country: "US", Name: "N", City: "line\nbreak"},
	} {
		if _, err := Encode(url, m); !errors.Is(err, ErrValue) {
			t.Errorf("Encode(%+v): err = %v, want ErrValue", m, err)
		}
	}
	if _, err := EncodeFields([]Field{{"2", "x"}}); !errors.Is(err, ErrValue) {
		t.Errorf("a one-digit tag: err = %v, want ErrValue", err)
	}
}

// withCRC appends tag 63 with the right CRC, so a case fails for its own defect only.
func withCRC(body string) string {
	body += "6304"
	return body + crcHex(CRC16([]byte(body)))
}

// S8.1-AC3: malformed input is refused with an error, never a panic.
func TestDecodeRefusesMalformedInput(t *testing.T) {
	good := golden(t)[0].QRCode
	x9 := "26800006org.x90166x9-150.example.com/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5"
	for _, tc := range []struct {
		name string
		in   string
		want error
	}{
		{"empty", "", ErrMalformed},
		{"bad CRC", good[:len(good)-4] + "0000", ErrCRC},
		{"CRC not hex", good[:len(good)-4] + "ZZZZ", ErrMalformed},
		{"no CRC", good[:len(good)-8], ErrMalformed},
		{"CRC not last", withCRC("6304AAAA" + "000201"), ErrMalformed},
		{"CRC too short", withCRC("000201")[:len(withCRC("000201"))-8] + "6303ABC", ErrMalformed},
		{"truncated", good[:len(good)-30], ErrMalformed},
		{"truncated header", "000", ErrMalformed},
		{"length beyond the end", "000501", ErrMalformed},
		{"length not digits", withCRC("00AB01"), ErrMalformed},
		{"length zero", withCRC("0000" + "000201"), ErrMalformed},
		{"tag not digits", withCRC("A00201"), ErrMalformed},
		{"duplicate tag", withCRC("000201000201" + x9), ErrMalformed},
		{"not UTF-8", "\xff\xfe", ErrMalformed},
		{"no template", withCRC("000201010212"), ErrNoX9},
		{"wrong GUI", withCRC("000201" + "26220006org.x80108x9.a/loc"), ErrNoX9},
		{"org.x9 without URL", withCRC("000201" + "26100006org.x9"), ErrNoX9},
		{"template not TLV", withCRC("000201" + "2603abc"), ErrMalformed},
		{"template truncated", withCRC("000201" + "26140006org.x90199"), ErrMalformed},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := Decode(tc.in); !errors.Is(err, tc.want) {
				t.Fatalf("err = %v, want %v", err, tc.want)
			}
		})
	}
}

// The decoder finds org.x9 among other templates and other tags, and reads a lower-case CRC.
func TestDecodeFindsX9AmongOthers(t *testing.T) {
	s := withCRC("000201" + "26220006org.x80108x9.a/loc" + "27220006org.x90108x9.b/loc" + "62070503***")
	q, err := Decode(s)
	if err != nil {
		t.Fatal(err)
	}
	if q.URL != "x9.b/loc" {
		t.Fatalf("URL %q", q.URL)
	}
	lower := s[:len(s)-4] + strings.ToLower(s[len(s)-4:])
	if _, err := Decode(lower); err != nil {
		t.Fatalf("lower-case CRC: %v", err)
	}
}

// Lengths count UTF-16 code units, as Java's String.length() does.
func TestDecodeCountsUTF16(t *testing.T) {
	fields, err := DecodeFields("5904Zé😀")
	if err != nil {
		t.Fatal(err)
	}
	if fields[0].Value != "Zé😀" || fields[0].Len() != 4 {
		t.Fatalf("got %+v len %d", fields, fields[0].Len())
	}
	if _, err := DecodeFields("5902Z😀"); !errors.Is(err, ErrMalformed) {
		t.Fatalf("a length that splits a surrogate pair: err = %v", err)
	}
}

// S8.1-AC3: the decoder never panics, and whatever it accepts it read exactly: its fields,
// written back, are the input.
func FuzzDecode(f *testing.F) {
	for _, g := range golden(f) {
		f.Add(g.QRCode)
	}
	f.Add("")
	f.Add("6304FFFF")
	f.Add(withCRC("000201" + "26220006org.x80108x9.a/loc"))
	f.Add("5904Zé😀")
	f.Fuzz(func(t *testing.T, s string) {
		if fields, err := DecodeFields(s); err == nil {
			var b strings.Builder
			for _, fl := range fields {
				b.WriteString(fl.Tag + twoDigits(fl.Len()) + fl.Value)
			}
			if b.String() != s {
				t.Fatalf("fields %+v written back are %q, not %q", fields, b.String(), s)
			}
		}
		q, err := Decode(s)
		if err != nil {
			return
		}
		if q.URL == "" || q.CRC != q.Fields[len(q.Fields)-1].Value {
			t.Fatalf("accepted %q without a URL or a CRC: %+v", s, q)
		}
	})
}
