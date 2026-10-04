// Command control-plane is the Lambda that launches and terminates phone
// Exits, so the phone never holds AWS credentials.
package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/bontaramsonta/poof/exit"
	"github.com/bontaramsonta/poof/wgkey"
)

const (
	clientTagKey   = "poof:client"
	clientTagValue = "android"

	serverTunnelIP  = "10.66.0.1"
	clientTunnelIP  = "10.66.0.2"
	wgPort          = 51820
	idleShutdownMin = 5

	// deadlineMargin is kept back from the Lambda deadline so a stuck
	// public-IP wait still leaves time to terminate the instance.
	deadlineMargin = 5 * time.Second
)

var phoneTags = map[string]string{clientTagKey: clientTagValue}

var instanceIDPattern = regexp.MustCompile(`^i-[0-9a-f]{8,17}$`)

// Region is the slice of poof's exit.Provisioner the handler uses, so
// tests can swap in a fake EC2.
type Region interface {
	EnsureSecurityGroup(ctx context.Context) (string, error)
	RunInstance(ctx context.Context, userData, securityGroupID string, extraTags map[string]string) (string, error)
	WaitForPublicIP(ctx context.Context, instanceID string) (string, error)
	Describe(ctx context.Context, instanceID string) (*exit.Instance, error)
	FindLive(ctx context.Context, tags map[string]string) ([]exit.Instance, error)
	Terminate(ctx context.Context, instanceID string) error
}

// Handler serves the control plane API behind a Lambda function URL.
type Handler struct {
	tokenHash [sha256.Size]byte
	region    func(name string) Region
	log       *slog.Logger
}

// NewHandler builds a Handler. region returns the Region for an AWS
// region name.
func NewHandler(token string, region func(name string) Region, log *slog.Logger) *Handler {
	return &Handler{tokenHash: sha256.Sum256([]byte(token)), region: region, log: log}
}

// reqLog accumulates the fields of the request's single log line.
type reqLog struct {
	route    string
	status   int
	country  string
	region   string
	instance string
	err      error
	timings  []slog.Attr
	started  time.Time
}

func (l *reqLog) time(step string, since time.Time) {
	l.timings = append(l.timings, slog.Int64(step+"_ms", time.Since(since).Milliseconds()))
}

// Handle routes one function URL request.
func (h *Handler) Handle(ctx context.Context, req events.LambdaFunctionURLRequest) (events.LambdaFunctionURLResponse, error) {
	rl := &reqLog{started: time.Now()}
	resp := h.route(ctx, req, rl)
	rl.status = resp.StatusCode
	h.emit(ctx, rl)
	return resp, nil
}

func (h *Handler) emit(ctx context.Context, rl *reqLog) {
	attrs := []slog.Attr{
		slog.String("route", rl.route),
		slog.Int("status", rl.status),
	}
	if rl.country != "" {
		attrs = append(attrs, slog.String("country", rl.country))
	}
	if rl.region != "" {
		attrs = append(attrs, slog.String("region", rl.region))
	}
	if rl.instance != "" {
		attrs = append(attrs, slog.String("instance", rl.instance))
	}
	if rl.err != nil {
		attrs = append(attrs, slog.String("error", rl.err.Error()))
	}
	timings := append(rl.timings, slog.Int64("total_ms", time.Since(rl.started).Milliseconds()))
	attrs = append(attrs, slog.Any("timings", slog.GroupValue(timings...)))
	h.log.LogAttrs(ctx, slog.LevelInfo, "request", attrs...)
}

func (h *Handler) route(ctx context.Context, req events.LambdaFunctionURLRequest, rl *reqLog) events.LambdaFunctionURLResponse {
	method := req.RequestContext.HTTP.Method
	parts := strings.Split(strings.Trim(req.RawPath, "/"), "/")

	switch {
	case len(parts) == 1 && parts[0] == "countries":
		rl.route = method + " /countries"
	case len(parts) == 1 && parts[0] == "exits":
		rl.route = method + " /exits"
	case len(parts) == 3 && parts[0] == "exits":
		rl.route = method + " /exits/{region}/{id}"
	default:
		rl.route = method + " unknown"
	}

	if !h.authorized(req.Headers) {
		return errorResponse(http.StatusUnauthorized, "unauthorized")
	}

	switch rl.route {
	case "GET /countries":
		return h.countries()
	case "POST /exits":
		return h.createExit(ctx, req, rl)
	case "GET /exits/{region}/{id}", "DELETE /exits/{region}/{id}":
		region, id := parts[1], parts[2]
		rl.region, rl.instance = region, id
		if !knownRegion(region) || !instanceIDPattern.MatchString(id) {
			return errorResponse(http.StatusBadRequest, "unknown region or malformed instance id")
		}
		if method == http.MethodGet {
			return h.getExit(ctx, region, id, rl)
		}
		return h.deleteExit(ctx, region, id, rl)
	}
	return errorResponse(http.StatusNotFound, "not found")
}

func (h *Handler) authorized(headers map[string]string) bool {
	// Function URLs lowercase header names.
	got, ok := strings.CutPrefix(headers["authorization"], "Bearer ")
	if !ok || got == "" {
		return false
	}
	sum := sha256.Sum256([]byte(got))
	return subtle.ConstantTimeCompare(sum[:], h.tokenHash[:]) == 1
}

func (h *Handler) countries() events.LambdaFunctionURLResponse {
	return jsonResponse(http.StatusOK, map[string][]string{"countries": exit.Countries()})
}

type createRequest struct {
	Country         string `json:"country"`
	ClientPublicKey string `json:"clientPublicKey"`
}

type createdExit struct {
	InstanceID      string `json:"instanceId"`
	Region          string `json:"region"`
	PublicIP        string `json:"publicIp"`
	ServerPublicKey string `json:"serverPublicKey"`
}

type existingExit struct {
	Country    string `json:"country"`
	Region     string `json:"region"`
	InstanceID string `json:"instanceId"`
	PublicIP   string `json:"publicIp"`
}

func (h *Handler) createExit(ctx context.Context, req events.LambdaFunctionURLRequest, rl *reqLog) events.LambdaFunctionURLResponse {
	var body createRequest
	if err := json.Unmarshal([]byte(req.Body), &body); err != nil {
		return errorResponse(http.StatusBadRequest, "body must be JSON {country, clientPublicKey}")
	}
	region, err := exit.RegionFor(body.Country)
	if err != nil {
		return errorResponse(http.StatusBadRequest, "unknown country")
	}
	rl.country, rl.region = strings.ToLower(strings.TrimSpace(body.Country)), region
	clientPub, err := wgkey.ParseBase64(body.ClientPublicKey)
	if err != nil {
		return errorResponse(http.StatusBadRequest, "clientPublicKey must be a base64 WireGuard key")
	}

	step := time.Now()
	live, err := h.livePhoneExits(ctx)
	rl.time("cap_check", step)
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusBadGateway, "could not check for a running phone Exit")
	}
	if len(live) > 0 {
		e := live[0]
		rl.instance = e.InstanceID
		return jsonResponse(http.StatusConflict, existingExit{
			Country: countryFor(e.Region), Region: e.Region, InstanceID: e.InstanceID, PublicIP: e.PublicIP,
		})
	}

	serverPriv, err := wgkey.GeneratePrivate()
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusInternalServerError, "key generation failed")
	}
	userData, err := exit.RenderUserData(exit.ExitParams{
		ServerPrivate:   serverPriv,
		ClientPublic:    clientPub,
		ServerTunnelIP:  serverTunnelIP,
		ClientTunnelIP:  clientTunnelIP,
		ListenPort:      wgPort,
		IdleShutdownMin: idleShutdownMin,
	})
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusInternalServerError, "rendering user-data failed")
	}

	r := h.region(region)
	step = time.Now()
	sgID, err := r.EnsureSecurityGroup(ctx)
	rl.time("security_group", step)
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusBadGateway, "could not prepare the security group")
	}

	step = time.Now()
	id, err := r.RunInstance(ctx, userData, sgID, phoneTags)
	rl.time("run_instances", step)
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusBadGateway, "could not launch the Exit")
	}
	rl.instance = id

	waitCtx := ctx
	if deadline, ok := ctx.Deadline(); ok {
		var cancel context.CancelFunc
		waitCtx, cancel = context.WithDeadline(ctx, deadline.Add(-deadlineMargin))
		defer cancel()
	}
	step = time.Now()
	ip, err := r.WaitForPublicIP(waitCtx, id)
	rl.time("public_ip", step)
	if err != nil {
		rl.err = err
		if terr := r.Terminate(context.WithoutCancel(ctx), id); terr != nil {
			rl.err = errors.Join(err, terr)
		}
		return errorResponse(http.StatusBadGateway, "the Exit got no public IP; it was terminated")
	}

	return jsonResponse(http.StatusCreated, createdExit{
		InstanceID: id, Region: region, PublicIP: ip, ServerPublicKey: serverPriv.Public().Base64(),
	})
}

// livePhoneExits finds pending or running phone Exits in every Country's
// region. Any region that cannot be read fails the whole check: the cap
// fails closed.
func (h *Handler) livePhoneExits(ctx context.Context) ([]exit.Instance, error) {
	regions := exit.AllRegions()
	results := make([][]exit.Instance, len(regions))
	errs := make([]error, len(regions))
	var wg sync.WaitGroup
	for i, name := range regions {
		wg.Go(func() {
			results[i], errs[i] = h.region(name).FindLive(ctx, phoneTags)
		})
	}
	wg.Wait()
	if err := errors.Join(errs...); err != nil {
		return nil, err
	}
	var all []exit.Instance
	for _, r := range results {
		all = append(all, r...)
	}
	return all, nil
}

func (h *Handler) getExit(ctx context.Context, region, id string, rl *reqLog) events.LambdaFunctionURLResponse {
	inst, err := h.region(region).Describe(ctx, id)
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusBadGateway, "could not describe the Exit")
	}
	state := "terminated" // EC2 forgets terminated instances after about an hour.
	if inst != nil {
		state = inst.State
	}
	return jsonResponse(http.StatusOK, map[string]string{"state": state})
}

func (h *Handler) deleteExit(ctx context.Context, region, id string, rl *reqLog) events.LambdaFunctionURLResponse {
	r := h.region(region)
	inst, err := r.Describe(ctx, id)
	if err != nil {
		rl.err = err
		return errorResponse(http.StatusBadGateway, "could not describe the Exit")
	}
	if inst == nil || inst.State == "terminated" {
		return events.LambdaFunctionURLResponse{StatusCode: http.StatusNoContent}
	}
	if inst.Tags[clientTagKey] != clientTagValue {
		return errorResponse(http.StatusForbidden, "not a phone Exit")
	}
	if err := r.Terminate(ctx, id); err != nil {
		rl.err = err
		return errorResponse(http.StatusBadGateway, "could not terminate the Exit")
	}
	return events.LambdaFunctionURLResponse{StatusCode: http.StatusNoContent}
}

func knownRegion(name string) bool {
	for _, r := range exit.AllRegions() {
		if r == name {
			return true
		}
	}
	return false
}

func countryFor(region string) string {
	for _, c := range exit.Countries() {
		if r, _ := exit.RegionFor(c); r == region {
			return c
		}
	}
	return ""
}

func jsonResponse(status int, v any) events.LambdaFunctionURLResponse {
	b, err := json.Marshal(v)
	if err != nil {
		panic(fmt.Sprintf("marshal response: %v", err))
	}
	return events.LambdaFunctionURLResponse{
		StatusCode: status,
		Headers:    map[string]string{"Content-Type": "application/json"},
		Body:       string(b),
	}
}

func errorResponse(status int, msg string) events.LambdaFunctionURLResponse {
	return jsonResponse(status, map[string]string{"error": msg})
}
