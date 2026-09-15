package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
)

// Why these four and not "whatever Go makes easy": the twin exists to do the SAME work as xyk, and a
// column that verifies less than the other one measures a different service. The schemes, their
// headers and their concatenations are the ones read from the vendors' documentation and recorded in
// docs/research; this file is a second implementation of that reading, which is also why a
// disagreement between the two columns is worth looking at rather than averaging.

type failure int

const (
	ok failure = iota
	missing
	invalid
	stale
)

// verify returns the outcome and, when it passed, the fingerprint of the secret that matched.
func verify(scheme string, r *http.Header, body []byte, secrets []secret, now int64, cfg schemeConfig) (failure, string) {
	switch scheme {
	case "github":
		return verifyGithub(r, body, secrets)
	case "stripe":
		return verifyStripe(r, body, secrets, now, cfg.toleranceSeconds)
	case "telegram":
		return verifyTelegram(r, secrets)
	case "hmac-sha256":
		return verifyGeneric(r, body, secrets, cfg)
	case "none":
		return ok, ""
	default:
		// An endpoint whose scheme nothing implements is refused like an unknown endpoint rather
		// than accepted unverified — the same choice the Kotlin side makes, and for the same reason.
		return invalid, ""
	}
}

func verifyGithub(h *http.Header, body []byte, secrets []secret) (failure, string) {
	offered := strings.TrimSpace(h.Get("X-Hub-Signature-256"))
	if offered == "" {
		return missing, ""
	}
	if !strings.HasPrefix(offered, "sha256=") {
		return invalid, ""
	}
	want := []byte(strings.ToLower(strings.TrimPrefix(offered, "sha256=")))
	for _, s := range secrets {
		if hmac.Equal(hexMac(s.value, body), want) {
			return ok, s.fingerprint
		}
	}
	return invalid, ""
}

func verifyStripe(h *http.Header, body []byte, secrets []secret, now int64, tolerance int64) (failure, string) {
	header := strings.TrimSpace(h.Get("Stripe-Signature"))
	if header == "" {
		return missing, ""
	}
	var timestamp int64
	var candidates [][]byte
	for _, part := range strings.Split(header, ",") {
		name, value, found := strings.Cut(strings.TrimSpace(part), "=")
		if !found {
			continue
		}
		switch name {
		case "t":
			timestamp, _ = strconv.ParseInt(value, 10, 64)
		case "v1":
			// v1 and nothing else: Stripe's own downgrade defence, and the v0 it sends for test
			// events would otherwise be a way in.
			candidates = append(candidates, []byte(strings.ToLower(value)))
		}
	}
	if timestamp == 0 || len(candidates) == 0 {
		return invalid, ""
	}
	signed := append([]byte(strconv.FormatInt(timestamp, 10)+"."), body...)
	matched := ""
	for _, s := range secrets {
		want := hexMac(s.value, signed)
		for _, candidate := range candidates {
			if hmac.Equal(want, candidate) {
				matched = s.fingerprint
			}
		}
	}
	if matched == "" {
		return invalid, ""
	}
	// Only after the signature matched: answering "stale" for a payload whose signature is wrong
	// tells an attacker which half they got right.
	drift := now - timestamp
	if drift > tolerance || drift < -tolerance {
		return stale, ""
	}
	return ok, matched
}

func verifyTelegram(h *http.Header, secrets []secret) (failure, string) {
	offered := h.Get("X-Telegram-Bot-Api-Secret-Token")
	if offered == "" {
		return missing, ""
	}
	for _, s := range secrets {
		if hmac.Equal([]byte(s.value), []byte(offered)) {
			return ok, s.fingerprint
		}
	}
	return invalid, ""
}

func verifyGeneric(h *http.Header, body []byte, secrets []secret, cfg schemeConfig) (failure, string) {
	if cfg.header == "" {
		return invalid, ""
	}
	raw := strings.TrimSpace(h.Get(cfg.header))
	if raw == "" {
		return missing, ""
	}
	if cfg.prefix != "" && !strings.HasPrefix(raw, cfg.prefix) {
		return invalid, ""
	}
	offered := strings.TrimPrefix(raw, cfg.prefix)
	for _, s := range secrets {
		mac := hmac.New(sha256.New, []byte(s.value))
		mac.Write(body)
		sum := mac.Sum(nil)
		var want []byte
		if strings.EqualFold(cfg.encoding, "base64") {
			want = []byte(base64.StdEncoding.EncodeToString(sum))
			if hmac.Equal(want, []byte(offered)) {
				return ok, s.fingerprint
			}
			continue
		}
		want = []byte(hex.EncodeToString(sum))
		if hmac.Equal(want, []byte(strings.ToLower(offered))) {
			return ok, s.fingerprint
		}
	}
	return invalid, ""
}

func hexMac(key string, message []byte) []byte {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write(message)
	return []byte(hex.EncodeToString(mac.Sum(nil)))
}
