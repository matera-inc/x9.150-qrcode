// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/json/jsontext"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Timestamp is a `format: date-time` member, read as the Java build reads it (ObjectMapperFactory's
// X9_UTC_TIMESTAMP, ANSI X9.150 Table 2): yyyy-MM-ddTHH:mm:ss, an optional fraction of one to three
// digits, and a literal Z. An offset such as +00:00, four fractional digits, a missing second or a
// lower-case z is malformed. Like Java's SMART resolver, a day past the month's end (up to 31) is
// read as its last day, and 24:00:00 as the next midnight. It is written back as Java writes an
// instant: whole seconds without a fraction, otherwise three digits.
type Timestamp struct {
	presence
	Value time.Time // UTC
}

// At is a Timestamp holding t.
func At(t time.Time) Timestamp { return Timestamp{Present: true, Value: t.UTC()} }

var x9Timestamp = regexp.MustCompile(`^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z$`)

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (t *Timestamp) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*t = Timestamp{Present: true}
	text, null, err := scalarText(dec)
	if err != nil || null {
		t.Null = null
		return err
	}
	v, ok := ParseTimestamp(text)
	if !ok {
		return errMalformed
	}
	t.Value = v
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (t Timestamp) MarshalJSONTo(enc *jsontext.Encoder) error {
	if t.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.String(FormatInstant(t.Value)))
}

// ParseTimestamp reads s as Timestamp does.
func ParseTimestamp(s string) (time.Time, bool) {
	m := x9Timestamp.FindStringSubmatch(s)
	if m == nil {
		return time.Time{}, false
	}
	n := make([]int, 6)
	for i := range n {
		n[i], _ = strconv.Atoi(m[i+1])
	}
	year, month, day, hour, minute, second := n[0], n[1], n[2], n[3], n[4], n[5]
	millis := 0
	if m[7] != "" {
		millis, _ = strconv.Atoi((m[7] + "00")[:3])
	}
	if year < 1 || month < 1 || month > 12 || day < 1 || day > 31 || minute > 59 || second > 59 {
		return time.Time{}, false
	}
	nextDay := false
	switch {
	case hour == 24 && minute == 0 && second == 0 && millis == 0:
		hour, nextDay = 0, true
	case hour > 23:
		return time.Time{}, false
	}
	day = min(day, daysIn(year, time.Month(month)))
	v := time.Date(year, time.Month(month), day, hour, minute, second, millis*int(time.Millisecond), time.UTC)
	if nextDay {
		v = v.AddDate(0, 0, 1)
	}
	return v, true
}

// FormatInstant writes t as Java's Instant.toString writes it once truncated to milliseconds:
// 2030-12-31T23:59:59Z, or 2030-12-31T23:59:59.500Z.
func FormatInstant(t time.Time) string {
	t = t.UTC().Truncate(time.Millisecond)
	if t.Nanosecond() == 0 {
		return t.Format("2006-01-02T15:04:05Z")
	}
	return t.Format("2006-01-02T15:04:05.000Z")
}

func daysIn(year int, month time.Month) int {
	return time.Date(year, month+1, 0, 0, 0, 0, 0, time.UTC).Day()
}

// Date is a `format: date` member, read as Jackson's LocalDateDeserializer reads it: yyyy-MM-dd,
// strictly; a date-time is also taken for its date (ending in Z, as an instant in UTC; otherwise as a
// local date-time); a number is a day count from 1970-01-01; "" is null; anything else is malformed.
// It is written back as yyyy-MM-dd.
type Date struct {
	presence
	Value time.Time // midnight UTC of the date
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (d *Date) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*d = Date{Present: true}
	tok, err := dec.ReadToken()
	if err != nil {
		return err
	}
	switch tok.Kind() { //nolint:exhaustive // every other JSON kind is the default: malformed
	case 'n':
		d.Null = true
		return nil
	case '0':
		days, err := strconv.ParseInt(tok.String(), 10, 32)
		if err != nil {
			return errMalformed
		}
		d.Value = time.Unix(0, 0).UTC().AddDate(0, 0, int(days))
		return nil
	case '"':
	default:
		return errMalformed
	}
	text := strings.TrimSpace(tok.String())
	if text == "" {
		d.Null = true
		return nil
	}
	v, ok := ParseDate(text)
	if !ok {
		return errMalformed
	}
	d.Value = v
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (d Date) MarshalJSONTo(enc *jsontext.Encoder) error {
	if d.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.String(d.Value.Format(time.DateOnly)))
}

var (
	isoDate          = regexp.MustCompile(`^(\d{4})-(\d{2})-(\d{2})$`)
	isoLocalDateTime = regexp.MustCompile(`^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$`)
)

// ParseDate reads s as Date does.
func ParseDate(s string) (time.Time, bool) {
	if len(s) > 10 && s[10] == 'T' {
		if strings.HasSuffix(s, "Z") {
			v, err := time.Parse(time.RFC3339Nano, s)
			if err != nil {
				return time.Time{}, false
			}
			v = v.UTC()
			return time.Date(v.Year(), v.Month(), v.Day(), 0, 0, 0, 0, time.UTC), true
		}
		m := isoLocalDateTime.FindStringSubmatch(s)
		if m == nil || !validClock(m[2], m[3], m[4]) {
			return time.Time{}, false
		}
		return ParseDate(m[1])
	}
	m := isoDate.FindStringSubmatch(s)
	if m == nil {
		return time.Time{}, false
	}
	year, _ := strconv.Atoi(m[1])
	month, _ := strconv.Atoi(m[2])
	day, _ := strconv.Atoi(m[3])
	if month < 1 || month > 12 || day < 1 || day > daysIn(year, time.Month(month)) {
		return time.Time{}, false
	}
	return time.Date(year, time.Month(month), day, 0, 0, 0, 0, time.UTC), true
}

func validClock(hour, minute, second string) bool {
	h, _ := strconv.Atoi(hour)
	m, _ := strconv.Atoi(minute)
	s := 0
	if second != "" {
		s, _ = strconv.Atoi(second)
	}
	return h <= 23 && m <= 59 && s <= 59
}
