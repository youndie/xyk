// The Go twin of xyk's ingest path, and nothing else.
//
// It exists to be the second column: the same route, the same schemes, the same insert, the same
// statuses, so that a number next to xyk's says what the platform floor is under identical work. A
// fuller port — delivery, the journal, the registry — would measure how well the author writes Go.
//
// It is never shipped. No chart, no registry, no version.
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	// modernc.org/sqlite, the PURE GO driver, and that is the decision rather than the default.
	// mattn/go-sqlite3 is cgo, which would mean a dynamically linked binary and a base image — while
	// the arm it is being compared against is a static Kotlin/Native binary on `scratch`. Comparing
	// a cgo Go build with that would put the difference in the packaging rather than in the runtime.
	// The driver is named in every results table for the same reason.
	_ "modernc.org/sqlite"
)

type secret struct {
	value       string
	fingerprint string
}

type schemeConfig struct {
	toleranceSeconds int64
	header           string
	prefix           string
	encoding         string
}

type endpoint struct {
	id          string
	scheme      string
	secrets     []secret
	subscribers []string
	config      schemeConfig
}

type server struct {
	db           *sql.DB
	maxBodyBytes int64
}

func main() {
	path := os.Getenv("XYK_DB_PATH")
	if path == "" {
		log.Fatal("XYK_DB_PATH is required")
	}
	port := envOr("XYK_PORT", "8080")
	maxBody, _ := strconv.ParseInt(envOr("XYK_MAX_BODY_BYTES", "1048576"), 10, 64)

	// `busy_timeout`, and it is what makes this a fair arm rather than a fast one. Without it the
	// driver returns SQLITE_BUSY the moment two connections want the writer lock, the handler
	// answers `500`, and the column looks quick because half its requests gave up: measured at
	// 2 000 rps offered, 48.7% of them failed. The Kotlin side waits (its pool retries a busy
	// acquire), so a twin that does not wait is not doing the same work.
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		log.Fatalf("open: %v", err)
	}
	// The same pool size as the Kotlin side, for the same reason: every connection is one more
	// reader, and SQLite's automatic checkpoint is only ever PASSIVE. A twin with a different pool
	// would be measuring a different storage design.
	db.SetMaxOpenConns(2)

	if err := migrate(db); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	if err := bootstrap(db); err != nil {
		log.Fatalf("bootstrap: %v", err)
	}

	s := &server{db: db, maxBodyBytes: maxBody}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /hooks/{endpointId}", s.hook)
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "ready")
	})

	log.Printf("twin-go: listening on :%s, driver modernc.org/sqlite", port)
	log.Fatal(http.ListenAndServe(":"+port, mux))
}

// The SAME schema as xyk, statement for statement where it matters, so that the parity gate can
// compare stored rows and not just status codes.
func migrate(db *sql.DB) error {
	statements := []string{
		`CREATE TABLE IF NOT EXISTS endpoints (id TEXT PRIMARY KEY, scheme TEXT NOT NULL,
			enabled INTEGER NOT NULL DEFAULT 1, description TEXT NOT NULL DEFAULT '',
			created_at INTEGER NOT NULL, scheme_config TEXT)`,
		`CREATE TABLE IF NOT EXISTS endpoint_secrets (id TEXT PRIMARY KEY, endpoint_id TEXT NOT NULL,
			secret TEXT NOT NULL, fingerprint TEXT NOT NULL, created_at INTEGER NOT NULL,
			retires_at INTEGER)`,
		`CREATE TABLE IF NOT EXISTS subscribers (id TEXT PRIMARY KEY, endpoint_id TEXT NOT NULL,
			url TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, endpoint_id TEXT NOT NULL,
			received_at INTEGER NOT NULL, scheme TEXT NOT NULL, secret_fingerprint TEXT,
			content_type TEXT, body BLOB NOT NULL, body_bytes INTEGER NOT NULL, purged_at INTEGER)`,
		`CREATE TABLE IF NOT EXISTS deliveries (id TEXT PRIMARY KEY, event_id TEXT NOT NULL,
			subscriber_id TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL)`,
		`PRAGMA journal_mode = WAL`,
		`PRAGMA synchronous = NORMAL`,
	}
	for _, statement := range statements {
		if _, err := db.Exec(statement); err != nil {
			return err
		}
	}
	return nil
}

// The same one endpoint the Kotlin side takes from the environment, so one script can set up both.
func bootstrap(db *sql.DB) error {
	id := os.Getenv("XYK_BOOTSTRAP_ENDPOINT_ID")
	value := os.Getenv("XYK_BOOTSTRAP_SECRET")
	if id == "" || value == "" {
		return nil
	}
	scheme := envOr("XYK_BOOTSTRAP_SCHEME", "github")
	now := time.Now().Unix()
	if _, err := db.Exec(
		`INSERT INTO endpoints (id, scheme, enabled, description, created_at, scheme_config)
		 VALUES (?, ?, 1, 'twin', ?, ?)
		 ON CONFLICT(id) DO UPDATE SET scheme = excluded.scheme, enabled = 1,
		 scheme_config = excluded.scheme_config`,
		id, scheme, now, nullable(os.Getenv("XYK_BOOTSTRAP_SCHEME_CONFIG"))); err != nil {
		return err
	}
	fingerprint := fingerprintOf(value)
	var known int
	if err := db.QueryRow(`SELECT count(*) FROM endpoint_secrets WHERE endpoint_id = ? AND fingerprint = ?`,
		id, fingerprint).Scan(&known); err != nil {
		return err
	}
	if known == 0 {
		if _, err := db.Exec(`INSERT INTO endpoint_secrets (id, endpoint_id, secret, fingerprint, created_at)
			VALUES (?, ?, ?, ?, ?)`, newID(), id, value, fingerprint, now); err != nil {
			return err
		}
	}
	for _, url := range strings.Split(os.Getenv("XYK_BOOTSTRAP_SUBSCRIBERS"), ",") {
		url = strings.TrimSpace(url)
		if url == "" {
			continue
		}
		var exists int
		if err := db.QueryRow(`SELECT count(*) FROM subscribers WHERE endpoint_id = ? AND url = ?`,
			id, url).Scan(&exists); err != nil {
			return err
		}
		if exists == 0 {
			if _, err := db.Exec(`INSERT INTO subscribers (id, endpoint_id, url, enabled, created_at)
				VALUES (?, ?, ?, 1, ?)`, newID(), id, url, now); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *server) hook(w http.ResponseWriter, r *http.Request) {
	endpointID := r.PathValue("endpointId")

	// Checked before the read, like the other column: a limit that buffers first defends nothing.
	if r.ContentLength > s.maxBodyBytes {
		fail(w, http.StatusRequestEntityTooLarge, "body too large")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.maxBodyBytes))
	if err != nil {
		fail(w, http.StatusRequestEntityTooLarge, "body too large")
		return
	}

	ep, err := s.findEndpoint(endpointID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "not stored")
		return
	}
	if ep == nil {
		// The same answer for unknown and disabled: a distinct status would make the route an
		// oracle for which ids are real.
		fail(w, http.StatusNotFound, "unknown endpoint")
		return
	}

	outcome, fingerprint := verify(ep.scheme, &r.Header, body, ep.secrets, time.Now().Unix(), ep.config)
	switch outcome {
	case missing:
		fail(w, http.StatusUnauthorized, "signature missing")
		return
	case invalid:
		fail(w, http.StatusUnauthorized, "signature invalid")
		return
	case stale:
		fail(w, http.StatusUnauthorized, "signature stale")
		return
	}

	// THE PARITY GATE'S NEGATIVE CONTROL, and it lives here because the twin is a fixture rather
	// than a product. `TWIN_BREAK=drop-insert` answers `200` and stores nothing — the exact shape of
	// a twin that looks fast because it does less work. A gate that has never caught this has never
	// been shown able to catch anything.
	if os.Getenv("TWIN_BREAK") == "drop-insert" {
		writeJSON(w, http.StatusOK, map[string]string{"event": newID()})
		return
	}

	eventID, err := s.accept(ep, body, r.Header.Get("Content-Type"), fingerprint)
	if err != nil {
		fail(w, http.StatusInternalServerError, "not stored")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"event": eventID})
}

func (s *server) findEndpoint(id string) (*endpoint, error) {
	var scheme string
	var config sql.NullString
	err := s.db.QueryRow(`SELECT scheme, scheme_config FROM endpoints WHERE id = ? AND enabled = 1`, id).
		Scan(&scheme, &config)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	ep := &endpoint{id: id, scheme: scheme, config: parseConfig(config.String)}
	rows, err := s.db.Query(`SELECT secret, fingerprint FROM endpoint_secrets WHERE endpoint_id = ?
		ORDER BY created_at DESC`, id)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		var value, fingerprint string
		if err := rows.Scan(&value, &fingerprint); err != nil {
			return nil, err
		}
		ep.secrets = append(ep.secrets, secret{value: value, fingerprint: fingerprint})
	}

	subs, err := s.db.Query(`SELECT id FROM subscribers WHERE endpoint_id = ? AND enabled = 1`, id)
	if err != nil {
		return nil, err
	}
	defer func() { _ = subs.Close() }()
	for subs.Next() {
		var subscriberID string
		if err := subs.Scan(&subscriberID); err != nil {
			return nil, err
		}
		ep.subscribers = append(ep.subscribers, subscriberID)
	}
	return ep, nil
}

// One transaction: the event and one delivery row per enabled subscriber, exactly as the other
// column does it. That is the work being compared.
func (s *server) accept(ep *endpoint, body []byte, contentType, fingerprint string) (string, error) {
	eventID := newID()
	now := time.Now().Unix()
	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.Exec(`INSERT INTO events
		(id, endpoint_id, received_at, scheme, secret_fingerprint, content_type, body, body_bytes)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		eventID, ep.id, now, ep.scheme, nullable(fingerprint), nullable(contentType), body, len(body)); err != nil {
		return "", err
	}
	for _, subscriberID := range ep.subscribers {
		if _, err := tx.Exec(`INSERT INTO deliveries (id, event_id, subscriber_id, state, attempts, created_at)
			VALUES (?, ?, ?, 'pending', 0, ?)`, newID(), eventID, subscriberID, now); err != nil {
			return "", err
		}
	}
	return eventID, tx.Commit()
}

func parseConfig(raw string) schemeConfig {
	cfg := schemeConfig{toleranceSeconds: 300}
	if raw == "" {
		return cfg
	}
	var decoded struct {
		ToleranceSeconds *int64  `json:"toleranceSeconds"`
		Header           *string `json:"header"`
		Prefix           *string `json:"prefix"`
		Encoding         *string `json:"encoding"`
	}
	if err := json.Unmarshal([]byte(raw), &decoded); err != nil {
		return cfg
	}
	if decoded.ToleranceSeconds != nil {
		cfg.toleranceSeconds = *decoded.ToleranceSeconds
	}
	if decoded.Header != nil {
		cfg.header = *decoded.Header
	}
	if decoded.Prefix != nil {
		cfg.prefix = *decoded.Prefix
	}
	if decoded.Encoding != nil {
		cfg.encoding = *decoded.Encoding
	}
	return cfg
}

func fingerprintOf(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:4])
}

func newID() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

func nullable(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func fail(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
