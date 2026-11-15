// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package emv writes and reads the one EMV merchant-presented QR Code shape X9.150 uses, by hand
// (ADR-0027): top-level TLV fields, tag 26 carrying the GUI org.x9 and, in its sub-tag 01, the
// location URL, and a CRC16-CCITT in tag 63.
//
// Encode produces exactly the characters the Java build prints for the same QR Code: the same
// fields in the same order (00, 01, 26, 52, 53, 54, 58, 59, 60, 63), the same fixed values
// (initiation method 12, currency 840 and amount 0 whatever the bill says), the name cut to 25
// characters and the city to 15, a blank value left out, and the CRC in four upper-case hex
// digits. The golden set exported from Java (others/blackbox/golden) is its acceptance test.
//
// Decode reads any well-formed merchant-presented string and returns its fields and the location
// URL of its org.x9 template. It refuses, with an error and never a panic, a bad length, a
// truncated field, a wrong or missing CRC, and a string with no org.x9 template.
package emv

import (
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// The fixed parts of the shape.
const (
	// GUI is the globally unique identifier of X9.150's merchant account information template.
	GUI = "org.x9"
	// PayloadPath is the path of the location URL, between the host and the location id.
	PayloadPath = "/pub/api/v1/loc/"
	// LocationIDLength is the length of a location id: 32 upper-case hex digits.
	LocationIDLength = 32
	// MaxValueLength is the longest value an EMV field may carry.
	MaxValueLength = 99
	// MaxHostLength is the longest public host whose location URL still fits tag 26: 99, less
	// the GUI field (0006org.x9), the URL field's tag and length (01LL), the payload path and a
	// location id. 37.
	MaxHostLength = MaxValueLength - len("0006"+GUI) - len("01LL") - len(PayloadPath) - LocationIDLength

	// MaxNameLength and MaxCityLength are where Java cuts the merchant's name and city.
	MaxNameLength = 25
	MaxCityLength = 15
)

// The tags this package writes, in the order it writes them.
const (
	TagPayloadFormatIndicator  = "00"
	TagPointOfInitiationMethod = "01"
	TagMerchantAccountX9       = "26"
	TagMerchantCategoryCode    = "52"
	TagTransactionCurrency     = "53"
	TagTransactionAmount       = "54"
	TagCountryCode             = "58"
	TagMerchantName            = "59"
	TagMerchantCity            = "60"
	TagCRC                     = "63"

	// Sub-tags of the org.x9 template.
	SubTagGUI = "00"
	SubTagURL = "01"
)

// The values Java writes for every QR Code, whatever the request says.
const (
	payloadFormatIndicator  = "01"
	pointOfInitiationMethod = "12"
	transactionCurrency     = "840"
	transactionAmount       = "0"
)

// Errors returned by Decode and Encode. Each is wrapped with what was found.
var (
	ErrMalformed = errors.New("malformed EMV")
	ErrCRC       = errors.New("EMV CRC does not match")
	ErrNoX9      = errors.New("EMV has no org.x9 merchant account information")
	ErrValue     = errors.New("value cannot be written in EMV")
)

// DecodeError is why Decode or DecodeFields refused a string: one of ErrMalformed, ErrCRC and
// ErrNoX9, and the reason in the Java build's words (EmvReader), which the decoder's answer quotes
// (S10.7-AC1).
type DecodeError struct {
	Kind   error
	Reason string
}

func (e *DecodeError) Error() string { return e.Kind.Error() + ": " + e.Reason }

// Unwrap is Kind, for errors.Is.
func (e *DecodeError) Unwrap() error { return e.Kind }

// Reason is the reason err gives, in the Java build's words, when it is a *DecodeError; otherwise
// err's text.
func Reason(err error) string {
	if d, ok := errors.AsType[*DecodeError](err); ok {
		return d.Reason
	}
	return err.Error()
}

func malformed(format string, args ...any) *DecodeError {
	return &DecodeError{Kind: ErrMalformed, Reason: fmt.Sprintf(format, args...)}
}

// Field is one TLV field: a two-digit tag and its value. The length is the value's.
type Field struct {
	Tag   string
	Value string
}

// Len is the field's length as EMV counts it: in UTF-16 code units, as the Java build does. For
// the printable ASCII this package writes, it is the number of bytes.
func (f Field) Len() int {
	return utf16Len(f.Value)
}

// Merchant is what a QR Code shows of its creditor.
type Merchant struct {
	CategoryCode string // MCC, four digits
	Country      string // ISO 3166 alpha-2
	Name         string // cut to 25 characters
	City         string // cut to 15 characters
}

// QRCode is a decoded string.
type QRCode struct {
	// Fields are the top-level fields in the order they appear, tag 63 included.
	Fields []Field
	// CRC is tag 63's value, as written.
	CRC string
	// Template are the org.x9 template's fields, in order.
	Template []Field
	// URL is the org.x9 template's sub-tag 01: the location URL, without a scheme.
	URL string
}

// LocationURL is the URL tag 26 carries for a location id: the host, the payload path and the id,
// with no scheme, as Java writes it.
func LocationURL(host, locationID string) string {
	return host + PayloadPath + locationID
}

// CheckHost refuses a host whose location URLs would not fit tag 26.
func CheckHost(host string) error {
	if n := len(host); n > MaxHostLength {
		return fmt.Errorf("must be at most %d characters, so that its location URL fits EMV tag 26's %d; got %d",
			MaxHostLength, MaxValueLength, n)
	}
	for i := range len(host) {
		if c := host[i]; c <= ' ' || c > '~' {
			return errors.New("must be printable ASCII, with no space")
		}
	}
	return nil
}

// Encode writes the QR Code for a location URL and a merchant, byte for byte as Java does.
func Encode(locationURL string, m Merchant) (string, error) {
	template, err := EncodeFields([]Field{{SubTagGUI, GUI}, {SubTagURL, locationURL}})
	if err != nil {
		return "", fmt.Errorf("tag %s: %w", TagMerchantAccountX9, err)
	}
	body, err := EncodeFields([]Field{
		{TagPayloadFormatIndicator, payloadFormatIndicator},
		{TagPointOfInitiationMethod, pointOfInitiationMethod},
		{TagMerchantAccountX9, template},
		{TagMerchantCategoryCode, m.CategoryCode},
		{TagTransactionCurrency, transactionCurrency},
		{TagTransactionAmount, transactionAmount},
		{TagCountryCode, m.Country},
		{TagMerchantName, left(m.Name, MaxNameLength)},
		{TagMerchantCity, left(m.City, MaxCityLength)},
	})
	if err != nil {
		return "", err
	}
	body += TagCRC + "04"
	return body + crcHex(CRC16([]byte(body))), nil
}

// EncodeFields writes fields as TLV, in the order given. A blank value (empty, or spaces only) is
// left out, as Java leaves it out. A value must be printable ASCII and at most 99 characters.
func EncodeFields(fields []Field) (string, error) {
	var b strings.Builder
	for _, f := range fields {
		if !isTag(f.Tag) {
			return "", fmt.Errorf("%w: tag %q is not two digits", ErrValue, f.Tag)
		}
		if strings.Trim(f.Value, " ") == "" {
			continue
		}
		for i := range len(f.Value) {
			if c := f.Value[i]; c < ' ' || c > '~' {
				return "", fmt.Errorf("%w: tag %s holds a character outside printable ASCII", ErrValue, f.Tag)
			}
		}
		if len(f.Value) > MaxValueLength {
			return "", fmt.Errorf("%w: tag %s is %d characters, more than %d", ErrValue, f.Tag, len(f.Value), MaxValueLength)
		}
		b.WriteString(f.Tag)
		b.WriteString(twoDigits(len(f.Value)))
		b.WriteString(f.Value)
	}
	return b.String(), nil
}

// DecodeFields reads s as a sequence of TLV fields. Lengths count UTF-16 code units, as Java
// counts them. It refuses invalid UTF-8, a tag or length that is not two digits, a length of 0
// and a value that runs past the end.
func DecodeFields(s string) ([]Field, error) {
	if !utf8.ValidString(s) {
		return nil, malformed("it is not UTF-8")
	}
	fields := make([]Field, 0, 12) // a QR Code of this shape has ten
	// at is pos in UTF-16 code units, the offset Java names.
	for pos, at := 0, 0; pos < len(s); {
		if len(s)-pos < 4 {
			return nil, malformed("the field at offset %d is truncated", at)
		}
		tag, length := s[pos:pos+2], s[pos+2:pos+4]
		if !isTag(tag) {
			return nil, malformed("the tag at offset %d is not two digits", at)
		}
		if !isTag(length) || length == "00" {
			return nil, malformed("tag %s has a length that is not 01 to 99", tag)
		}
		n := int(length[0]-'0')*10 + int(length[1]-'0')
		start := pos + 4
		end, split := advanceUTF16(s, start, n)
		if end < 0 {
			if split {
				return nil, malformed("tag %s ends inside a character", tag)
			}
			return nil, malformed("tag %s declares %s characters, and fewer are left", tag, length)
		}
		fields = append(fields, Field{Tag: tag, Value: s[start:end]})
		pos, at = end, at+4+n
	}
	return fields, nil
}

// Decode reads a QR Code string: its fields, its CRC, and the location URL of its org.x9 template.
func Decode(s string) (QRCode, error) {
	fields, err := DecodeFields(s)
	if err != nil {
		return QRCode{}, err
	}
	if len(fields) == 0 {
		return QRCode{}, malformed("it is empty")
	}
	seen := make(map[string]bool, len(fields))
	for i, f := range fields {
		if seen[f.Tag] {
			return QRCode{}, malformed("tag %s appears twice", f.Tag)
		}
		seen[f.Tag] = true
		if f.Tag == TagCRC && i != len(fields)-1 {
			return QRCode{}, malformed("the CRC (tag 63) is not the last field")
		}
	}
	crc := fields[len(fields)-1]
	if crc.Tag != TagCRC {
		return QRCode{}, malformed("there is no CRC (tag 63) at the end")
	}
	if crc.Len() != 4 {
		return QRCode{}, malformed("the CRC (tag 63) has %d characters, not 4", crc.Len())
	}
	got, err := strconv.ParseUint(crc.Value, 16, 16)
	if err != nil {
		return QRCode{}, malformed("the CRC (tag 63) is not four hex digits")
	}
	if want := CRC16([]byte(s[:len(s)-4])); uint16(got) != want {
		return QRCode{}, &DecodeError{Kind: ErrCRC, Reason: "the CRC (tag 63) does not match its content"}
	}

	q := QRCode{Fields: fields, CRC: crc.Value}
	if err := q.findX9(); err != nil {
		return QRCode{}, err
	}
	return q, nil
}

// findX9 reads every merchant account information template (tags 26 to 51) and keeps the first
// whose GUI is org.x9, as Java does.
func (q *QRCode) findX9() error {
	var guis []string
	for _, f := range q.Fields {
		if f.Tag < "26" || f.Tag > "51" {
			continue
		}
		sub, err := DecodeFields(f.Value)
		if err != nil {
			return malformed("in tag %s, %s", f.Tag, Reason(err))
		}
		gui, url := value(sub, SubTagGUI), value(sub, SubTagURL)
		if gui != GUI {
			guis = append(guis, strconv.Quote(gui))
			continue
		}
		if url == "" {
			return &DecodeError{Kind: ErrNoX9, Reason: fmt.Sprintf("tag %s has GUI %s and no URL (sub-tag 01)", f.Tag, GUI)}
		}
		q.Template, q.URL = sub, url
		return nil
	}
	if len(guis) == 0 {
		return &DecodeError{Kind: ErrNoX9, Reason: "there is no merchant account information template (tags 26 to 51)"}
	}
	return &DecodeError{Kind: ErrNoX9, Reason: "the templates carry GUI " + strings.Join(guis, ", ")}
}

func value(fields []Field, tag string) string {
	if i := slices.IndexFunc(fields, func(f Field) bool { return f.Tag == tag }); i >= 0 {
		return fields[i].Value
	}
	return ""
}

// CRC16 is CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection, no final XOR), the CRC of EMV
// tag 63, computed over everything before its four hex digits.
func CRC16(data []byte) uint16 {
	crc := uint16(0xFFFF)
	for _, b := range data {
		crc ^= uint16(b) << 8
		for range 8 {
			if crc&0x8000 != 0 {
				crc = crc<<1 ^ 0x1021
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

func crcHex(crc uint16) string {
	const hex = "0123456789ABCDEF"
	return string([]byte{hex[crc>>12], hex[crc>>8&0xF], hex[crc>>4&0xF], hex[crc&0xF]})
}

func twoDigits(n int) string {
	if n < 10 {
		return "0" + strconv.Itoa(n)
	}
	return strconv.Itoa(n)
}

func isTag(s string) bool {
	return len(s) == 2 && s[0] >= '0' && s[0] <= '9' && s[1] >= '0' && s[1] <= '9'
}

// left keeps the first n characters, as Java's StringUtils.left does. The values cut here are
// printable ASCII, so a character is a byte.
func left(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

func utf16Len(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}

// advanceUTF16 returns the byte offset n UTF-16 code units after start, or -1 when s ends first,
// and -1 and true when the count would split a surrogate pair.
func advanceUTF16(s string, start, n int) (end int, split bool) {
	pos := start
	for n > 0 {
		if pos >= len(s) {
			return -1, false
		}
		r, size := utf8.DecodeRuneInString(s[pos:])
		units := utf16.RuneLen(r)
		if units > n {
			return -1, true
		}
		n -= units
		pos += size
	}
	return pos, false
}
