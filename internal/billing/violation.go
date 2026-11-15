// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

// Kind is which of Java's two refusals a Violation is. Both are answered 400; they differ in the
// problem type the HTTP layer writes.
type Kind int

const (
	// Invalid is a value that is malformed on its own: Java's ValueObjectRuleException and Bean
	// Validation, answered as validation-error.
	Invalid Kind = iota
	// BusinessRule is a value that is well formed but not acceptable here: Java's
	// BusinessRuleException, answered as business-rule-violation.
	BusinessRule
)

// String names the kind after the problem type it is answered with.
func (k Kind) String() string {
	switch k {
	case Invalid:
		return "validation-error"
	case BusinessRule:
		return "business-rule-violation"
	}
	return "unknown"
}

// Violation is one refused rule: the field Java names (empty when Java names none) and Java's
// message, word for word.
type Violation struct {
	Kind    Kind
	Field   string
	Message string
}

// Error is the text Java puts in the answer's violations array: "field: message", or the message
// alone when there is no field.
func (v *Violation) Error() string {
	if v.Field == "" {
		return v.Message
	}
	return v.Field + ": " + v.Message
}

func invalid(message string) *Violation {
	return &Violation{Kind: Invalid, Message: message}
}

func invalidField(field, message string) *Violation {
	return &Violation{Kind: Invalid, Field: field, Message: message}
}

func rule(field, message string) *Violation {
	return &Violation{Kind: BusinessRule, Field: field, Message: message}
}
