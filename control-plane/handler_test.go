package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"testing"

	"github.com/aws/aws-lambda-go/events"
	"github.com/bontaramsonta/poof/exit"
	"github.com/bontaramsonta/poof/wgkey"
)

const testToken = "s3cret-token-value-that-must-never-be-logged"

// fakeEC2 is one region of a fake EC2. All regions share mu via fleet.
type fakeEC2 struct {
	fleet *fakeFleet
	name  string
}

type fakeFleet struct {
	mu         sync.Mutex
	instances  map[string]*exit.Instance // by ID
	userData   map[string]string
	nextID     int
	noPublicIP bool
	findErr    error
	terminated []string
}

func newFleet() *fakeFleet {
	return &fakeFleet{instances: map[string]*exit.Instance{}, userData: map[string]string{}}
}

func (f *fakeFleet) region(name string) Region { return &fakeEC2{fleet: f, name: name} }

func (f *fakeFleet) add(region, state string, tags map[string]string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.nextID++
	id := fmt.Sprintf("i-%017x", f.nextID)
	f.instances[id] = &exit.Instance{Region: region, InstanceID: id, PublicIP: "203.0.113.9", State: state, Tags: tags}
	return id
}

func (e *fakeEC2) EnsureSecurityGroup(context.Context) (string, error) { return "sg-poof", nil }

func (e *fakeEC2) RunInstance(_ context.Context, userData, _ string, extraTags map[string]string) (string, error) {
	tags := map[string]string{exit.TagKey: exit.TagValue, "Name": "poof-exit"}
	for k, v := range extraTags {
		tags[k] = v
	}
	id := e.fleet.add(e.name, "pending", tags)
	e.fleet.mu.Lock()
	e.fleet.instances[id].PublicIP = ""
	e.fleet.userData[id] = userData
	e.fleet.mu.Unlock()
	return id, nil
}

func (e *fakeEC2) WaitForPublicIP(_ context.Context, id string) (string, error) {
	if e.fleet.noPublicIP {
		return "", errors.New("exit: waiting for public IP: context deadline exceeded")
	}
	e.fleet.mu.Lock()
	defer e.fleet.mu.Unlock()
	e.fleet.instances[id].PublicIP = "198.51.100.7"
	return "198.51.100.7", nil
}

func (e *fakeEC2) Describe(_ context.Context, id string) (*exit.Instance, error) {
	e.fleet.mu.Lock()
	defer e.fleet.mu.Unlock()
	inst, ok := e.fleet.instances[id]
	if !ok || inst.Region != e.name {
		return nil, nil
	}
	cp := *inst
	return &cp, nil
}

func (e *fakeEC2) FindLive(_ context.Context, tags map[string]string) ([]exit.Instance, error) {
	e.fleet.mu.Lock()
	defer e.fleet.mu.Unlock()
	if e.fleet.findErr != nil {
		return nil, e.fleet.findErr
	}
	var out []exit.Instance
	for _, inst := range e.fleet.instances {
		if inst.Region != e.name || (inst.State != "pending" && inst.State != "running") {
			continue
		}
		match := true
		for k, v := range tags {
			if inst.Tags[k] != v {
				match = false
			}
		}
		if match {
			out = append(out, *inst)
		}
	}
	return out, nil
}

func (e *fakeEC2) Terminate(_ context.Context, id string) error {
	e.fleet.mu.Lock()
	defer e.fleet.mu.Unlock()
	if inst, ok := e.fleet.instances[id]; ok {
		inst.State = "terminated"
	}
	e.fleet.terminated = append(e.fleet.terminated, id)
	return nil
}

type harness struct {
	t     *testing.T
	fleet *fakeFleet
	h     *Handler
	logs  *bytes.Buffer
}

func newHarness(t *testing.T) *harness {
	fleet := newFleet()
	logs := &bytes.Buffer{}
	return &harness{
		t:     t,
		fleet: fleet,
		h:     NewHandler(testToken, fleet.region, slog.New(slog.NewJSONHandler(logs, nil))),
		logs:  logs,
	}
}

func (hs *harness) do(method, path, token, body string) events.LambdaFunctionURLResponse {
	hs.t.Helper()
	req := events.LambdaFunctionURLRequest{RawPath: path, Body: body, Headers: map[string]string{}}
	req.RequestContext.HTTP.Method = method
	if token != "" {
		req.Headers["authorization"] = "Bearer " + token
	}
	resp, err := hs.h.Handle(context.Background(), req)
	if err != nil {
		hs.t.Fatal(err)
	}
	return resp
}

func clientKey(t *testing.T) string {
	t.Helper()
	priv, err := wgkey.GeneratePrivate()
	if err != nil {
		t.Fatal(err)
	}
	return priv.Public().Base64()
}

func createBody(country, key string) string {
	b, _ := json.Marshal(createRequest{Country: country, ClientPublicKey: key})
	return string(b)
}

func decode[T any](t *testing.T, resp events.LambdaFunctionURLResponse) T {
	t.Helper()
	var v T
	if err := json.Unmarshal([]byte(resp.Body), &v); err != nil {
		t.Fatalf("decoding %q: %v", resp.Body, err)
	}
	return v
}

func TestBadOrMissingTokenIs401(t *testing.T) {
	hs := newHarness(t)
	for _, token := range []string{"", "wrong", testToken + "x"} {
		for _, r := range [][2]string{{"GET", "/countries"}, {"POST", "/exits"}, {"DELETE", "/exits/ap-south-1/i-0123456789abcdef0"}} {
			if resp := hs.do(r[0], r[1], token, ""); resp.StatusCode != http.StatusUnauthorized {
				t.Errorf("%s %s with token %q: status %d, want 401", r[0], r[1], token, resp.StatusCode)
			}
		}
	}
}

func TestCountries(t *testing.T) {
	hs := newHarness(t)
	resp := hs.do("GET", "/countries", testToken, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	got := decode[map[string][]string](t, resp)["countries"]
	if len(got) != 12 {
		t.Fatalf("got %d countries, want 12: %v", len(got), got)
	}
}

func TestCreateReturnsExitAndTagsIt(t *testing.T) {
	hs := newHarness(t)
	resp := hs.do("POST", "/exits", testToken, createBody("Japan", clientKey(t)))
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status %d: %s", resp.StatusCode, resp.Body)
	}
	got := decode[createdExit](t, resp)
	if got.Region != "ap-northeast-1" || got.PublicIP != "198.51.100.7" || got.InstanceID == "" {
		t.Errorf("unexpected body %+v", got)
	}
	if _, err := wgkey.ParseBase64(got.ServerPublicKey); err != nil {
		t.Errorf("serverPublicKey: %v", err)
	}
	inst := hs.fleet.instances[got.InstanceID]
	if inst.Tags["poof:client"] != "android" || inst.Tags["poof"] != "1" {
		t.Errorf("launch tags = %v, want poof=1 and poof:client=android", inst.Tags)
	}
}

func TestCreateAcceptsBase64Body(t *testing.T) {
	hs := newHarness(t)
	req := events.LambdaFunctionURLRequest{
		RawPath:         "/exits",
		Body:            base64.StdEncoding.EncodeToString([]byte(createBody("japan", clientKey(t)))),
		IsBase64Encoded: true,
		Headers:         map[string]string{"authorization": "Bearer " + testToken},
	}
	req.RequestContext.HTTP.Method = "POST"
	resp, err := hs.h.Handle(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status %d: %s", resp.StatusCode, resp.Body)
	}
}

func TestCapReturns409WithExistingExit(t *testing.T) {
	hs := newHarness(t)
	id := hs.fleet.add("eu-west-2", "running", map[string]string{"poof": "1", "poof:client": "android"})

	resp := hs.do("POST", "/exits", testToken, createBody("japan", clientKey(t)))
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("status %d, want 409", resp.StatusCode)
	}
	got := decode[existingExit](t, resp)
	want := existingExit{Country: "britain", Region: "eu-west-2", InstanceID: id, PublicIP: "203.0.113.9"}
	if got != want {
		t.Errorf("got %+v, want %+v", got, want)
	}
}

func TestCapIgnoresCLIExits(t *testing.T) {
	hs := newHarness(t)
	hs.fleet.add("ap-northeast-1", "running", map[string]string{"poof": "1"})
	if resp := hs.do("POST", "/exits", testToken, createBody("japan", clientKey(t))); resp.StatusCode != http.StatusCreated {
		t.Fatalf("status %d, want 201 beside a CLI Exit", resp.StatusCode)
	}
}

func TestCapFailsClosed(t *testing.T) {
	hs := newHarness(t)
	hs.fleet.findErr = errors.New("throttled")
	if resp := hs.do("POST", "/exits", testToken, createBody("japan", clientKey(t))); resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("status %d, want 502", resp.StatusCode)
	}
	if len(hs.fleet.instances) != 0 {
		t.Error("launched despite an unreadable region")
	}
}

func TestFailureBeforePublicIPTerminates(t *testing.T) {
	hs := newHarness(t)
	hs.fleet.noPublicIP = true
	resp := hs.do("POST", "/exits", testToken, createBody("india", clientKey(t)))
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("status %d, want 502", resp.StatusCode)
	}
	if len(hs.fleet.terminated) != 1 {
		t.Fatalf("terminated %v, want the launched instance", hs.fleet.terminated)
	}
	if hs.fleet.instances[hs.fleet.terminated[0]].State != "terminated" {
		t.Error("instance left running")
	}
	got := decode[map[string]string](t, resp)
	if got["region"] != "ap-south-1" || got["instanceId"] != hs.fleet.terminated[0] {
		t.Errorf("failure body %v lacks region and instance ID", got)
	}
}

func TestCreateRejectsBadInput(t *testing.T) {
	hs := newHarness(t)
	for _, body := range []string{"", "{", createBody("narnia", clientKey(t)), createBody("japan", "short")} {
		if resp := hs.do("POST", "/exits", testToken, body); resp.StatusCode != http.StatusBadRequest {
			t.Errorf("body %q: status %d, want 400", body, resp.StatusCode)
		}
	}
}

func TestDeleteRefusesCLIExit(t *testing.T) {
	hs := newHarness(t)
	id := hs.fleet.add("ap-south-1", "running", map[string]string{"poof": "1"})
	if resp := hs.do("DELETE", "/exits/ap-south-1/"+id, testToken, ""); resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status %d, want 403", resp.StatusCode)
	}
	if hs.fleet.instances[id].State != "running" {
		t.Error("CLI Exit was terminated")
	}
}

func TestDeleteTerminatesPhoneExit(t *testing.T) {
	hs := newHarness(t)
	id := hs.fleet.add("ap-south-1", "running", map[string]string{"poof": "1", "poof:client": "android"})
	if resp := hs.do("DELETE", "/exits/ap-south-1/"+id, testToken, ""); resp.StatusCode != http.StatusNoContent {
		t.Fatalf("status %d, want 204", resp.StatusCode)
	}
	if hs.fleet.instances[id].State != "terminated" {
		t.Error("phone Exit still running")
	}
	// Idempotent: deleting a gone Exit is still 204.
	if resp := hs.do("DELETE", "/exits/ap-south-1/i-0000000000000dead", testToken, ""); resp.StatusCode != http.StatusNoContent {
		t.Errorf("deleting unknown instance: status %d, want 204", resp.StatusCode)
	}
}

func TestGetExitState(t *testing.T) {
	hs := newHarness(t)
	id := hs.fleet.add("sa-east-1", "running", map[string]string{"poof": "1", "poof:client": "android"})
	got := decode[map[string]string](t, hs.do("GET", "/exits/sa-east-1/"+id, testToken, ""))
	if got["state"] != "running" {
		t.Errorf("state %q, want running", got["state"])
	}
	got = decode[map[string]string](t, hs.do("GET", "/exits/sa-east-1/i-0000000000000dead", testToken, ""))
	if got["state"] != "terminated" {
		t.Errorf("unknown instance state %q, want terminated", got["state"])
	}
	if resp := hs.do("GET", "/exits/mars-1/"+id, testToken, ""); resp.StatusCode != http.StatusBadRequest {
		t.Errorf("unknown region: status %d, want 400", resp.StatusCode)
	}
}

func TestLogsOneLinePerRequestWithoutSecrets(t *testing.T) {
	hs := newHarness(t)
	key := clientKey(t)
	created := decode[createdExit](t, hs.do("POST", "/exits", testToken, createBody("japan", key)))
	hs.do("POST", "/exits", testToken, createBody("japan", key)) // 409
	hs.do("GET", "/countries", "wrong-token", "")
	hs.fleet.noPublicIP = true
	hs.do("DELETE", "/exits/ap-northeast-1/"+created.InstanceID, testToken, "")
	hs.do("POST", "/exits", testToken, createBody("india", key)) // public-IP failure

	lines := strings.Split(strings.TrimSpace(hs.logs.String()), "\n")
	if len(lines) != 5 {
		t.Fatalf("got %d log lines, want 5:\n%s", len(lines), hs.logs)
	}

	userData := hs.fleet.userData[created.InstanceID]
	serverPriv := between(userData, "PrivateKey = ", "\n")
	if serverPriv == "" {
		t.Fatal("could not find the server private key in the rendered user-data")
	}
	forbidden := map[string]string{
		"token":            testToken,
		"wrong token":      "wrong-token",
		"client key":       key,
		"server key":       serverPriv,
		"server pub key":   created.ServerPublicKey,
		"user-data":        "[Interface]",
		"user-data script": "#!/bin/bash",
	}
	for _, line := range lines {
		var m map[string]any
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			t.Fatalf("log line is not JSON: %q", line)
		}
		for name, s := range forbidden {
			if strings.Contains(line, s) {
				t.Errorf("log line contains the %s: %s", name, line)
			}
		}
	}
	if !strings.Contains(lines[0], `"run_instances_ms"`) || !strings.Contains(lines[0], `"public_ip_ms"`) {
		t.Errorf("create log line lacks step timings: %s", lines[0])
	}
}

func between(s, start, end string) string {
	_, rest, ok := strings.Cut(s, start)
	if !ok {
		return ""
	}
	v, _, _ := strings.Cut(rest, end)
	return strings.TrimSpace(v)
}
