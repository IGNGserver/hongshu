package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"testing"
)

// errorCodeContract is the shared, versioned list of error codes the hub can return.
// Web and Android map these codes to user-facing text, so a rename on either side must
// fail here instead of silently degrading to a generic message.
type errorCodeContract struct {
	Version int      `json:"version"`
	Codes   []string `json:"codes"`
	Dynamic struct {
		Patterns []string `json:"patterns"`
	} `json:"dynamic_codes"`
	AckErrors struct {
		Messages []string `json:"messages"`
		Calls    []string `json:"calls"`
	} `json:"ack_errors"`
}

var ackErrorPattern = regexp.MustCompile(`acks\[[^]]+\]\.Error = "([a-z_]+)"`)

func loadErrorCodeContract(t *testing.T) errorCodeContract {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "protocol", "error-codes.v1.json"))
	if err != nil {
		t.Fatalf("shared error-code contract is missing: %v", err)
	}
	var contract errorCodeContract
	if err = json.Unmarshal(raw, &contract); err != nil {
		t.Fatalf("shared error-code contract is malformed: %v", err)
	}
	if contract.Version != 1 {
		t.Fatalf("unexpected contract version: %d", contract.Version)
	}
	return contract
}

// emittedErrorCodes reads the production sources and collects every error code the hub
// can actually return: literals passed to fail(), plus the codes the AI failure path
// returns dynamically to fail(w, 502, code).
func emittedErrorCodes(t *testing.T) map[string]string {
	t.Helper()
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	codes := make(map[string]string)
	add := func(literal, where string) {
		code, unquoteErr := strconv.Unquote(literal)
		if unquoteErr != nil || code == "" {
			return
		}
		codes[code] = where
	}
	for _, file := range files {
		if len(file) > 8 && file[len(file)-8:] == "_test.go" {
			continue
		}
		parsed, err := parser.ParseFile(token.NewFileSet(), file, nil, 0)
		if err != nil {
			t.Fatalf("cannot parse %s: %v", file, err)
		}
		ast.Inspect(parsed, func(node ast.Node) bool {
			call, ok := node.(*ast.CallExpr)
			if !ok || len(call.Args) != 3 {
				return true
			}
			ident, ok := call.Fun.(*ast.Ident)
			if !ok || (ident.Name != "fail" && ident.Name != "writeError") {
				return true
			}
			if literal, ok := call.Args[2].(*ast.BasicLit); ok && literal.Kind == token.STRING {
				add(literal.Value, file)
			}
			return true
		})
		// aiHTTPFailure returns the code that testAI hands to fail(w, 502, code). Only
		// returned literals are codes; the same function also holds provider markers.
		for _, decl := range parsed.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok || (fn.Name.Name != "aiHTTPFailure" && fn.Name.Name != "aiErrorDescriptor") {
				continue
			}
			ast.Inspect(fn, func(node ast.Node) bool {
				ret, ok := node.(*ast.ReturnStmt)
				if !ok || len(ret.Results) == 0 {
					return true
				}
				if literal, ok := ret.Results[0].(*ast.BasicLit); ok && literal.Kind == token.STRING {
					add(literal.Value, file)
				}
				return true
			})
		}
	}
	return codes
}

// compareAckErrors checks the per-item upload results, which travel inside a 200
// acks[] entry rather than as a top-level error body and so never reach fail().
func compareAckErrors(contract errorCodeContract) error {
	for file, want := range map[string][]string{
		"upload_batch.go": contract.AckErrors.Messages,
		"calls_batch.go":  contract.AckErrors.Calls,
	} {
		source, err := os.ReadFile(file)
		if err != nil {
			return err
		}
		emitted := make(map[string]bool)
		for _, match := range ackErrorPattern.FindAllSubmatch(source, -1) {
			emitted[string(match[1])] = true
		}
		for _, code := range want {
			if !emitted[code] {
				return fmt.Errorf("%s no longer emits ack error %q; update protocol/error-codes.v1.json", file, code)
			}
			delete(emitted, code)
		}
		for code := range emitted {
			return fmt.Errorf("%s emits undocumented ack error %q; add it to protocol/error-codes.v1.json", file, code)
		}
	}
	return nil
}

func TestSharedErrorCodeContractMatchesHub(t *testing.T) {
	contract := loadErrorCodeContract(t)
	declared := make(map[string]bool, len(contract.Codes))
	for _, code := range contract.Codes {
		if declared[code] {
			t.Fatalf("duplicate code in shared contract: %s", code)
		}
		declared[code] = true
	}
	emitted := emittedErrorCodes(t)
	var missing, stale []string
	for code, where := range emitted {
		if !declared[code] {
			missing = append(missing, code+" ("+where+")")
		}
	}
	for code := range declared {
		if _, ok := emitted[code]; !ok {
			stale = append(stale, code)
		}
	}
	sort.Strings(missing)
	sort.Strings(stale)
	if len(missing) > 0 || len(stale) > 0 {
		t.Fatalf("shared contract and hub disagree; add to protocol/error-codes.v1.json: %v; remove: %v", missing, stale)
	}
	if len(contract.Codes) < 40 {
		t.Fatalf("contract covers only %d codes; extraction probably broke", len(contract.Codes))
	}
	if err := compareAckErrors(contract); err != nil {
		t.Fatal(err)
	}
	// Runtime-assembled AI codes are documented as patterns; keep both sides in sync.
	// Scan the whole package rather than one file so moving code cannot silently
	// disable this check.
	var source []byte
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		if len(file) > 8 && file[len(file)-8:] == "_test.go" {
			continue
		}
		body, readErr := os.ReadFile(file)
		if readErr != nil {
			t.Fatal(readErr)
		}
		source = append(source, body...)
	}
	if len(contract.Dynamic.Patterns) == 0 {
		t.Fatal("contract must document the runtime-assembled AI codes")
	}
	for _, pattern := range []string{`"ai_http_%d"`, `"ai_http_%d_unknown"`} {
		if !bytes.Contains(source, []byte(pattern)) {
			t.Fatalf("the hub no longer assembles %s; update the contract", pattern)
		}
	}
}
