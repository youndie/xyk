// The ingest scenario, for k6.
//
// OPEN MODEL, and that is the whole design rather than a setting. `constant-arrival-rate` offers a
// fixed number of requests per second whatever the service does with them; a closed loop (`vus`)
// offers the next request only when the previous one comes back, so a sagging server *receives less*
// and the run looks healthy right up to the point it is not. The failure this criterion is about —
// slower reads raising the number of requests in flight — cannot be reproduced by a closed loop at
// all, and `dropped_iterations` is how an open one admits it could not keep up.
import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.RATE || 2000);
const duration = __ENV.DURATION || '60s';
const connections = Number(__ENV.CONNECTIONS || 200);
const target = __ENV.TARGET;
const arm = __ENV.ARM || 'unknown';
// Enough that the generator cannot be the ceiling: the rate it has to offer, times a latency budget
// well past anything this service should reach, with a floor for low rates.
const vuCeiling = Number(__ENV.VUS || Math.max(500, rate * 2));
const body = __ENV.BODY || '';
const signature = __ENV.SIGNATURE || '';

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      // SIZED BY THE RATE, NOT BY THE CONNECTION COUNT — and the previous version of these two
      // lines is the reason this file has a paragraph about it.
      //
      // It read `maxVUs: connections`, on the reasoning that the criterion names 200 connections so
      // the generator must not open a 201st. But a VU under an open model is not a connection: it is
      // occupied for the whole round trip, so the pool caps **requests in flight**, and the highest
      // rate it can then offer is `VUs / latency`. At the 380 ms this service was showing, 200 VUs
      // cannot offer more than about 526 rps — which is exactly the band all three arms of the pilot
      // landed in, control included, and which was read at the time as a mysterious shared ceiling.
      // Sizing the pool by the connection count silently converts the open model back into a closed
      // one: the very thing the header above says this executor is here to avoid.
      //
      // Measured on the real pair (bench-b → bench-a, 2026-09-16): with a large pool the generator
      // delivers 8 000 rps at p50 0.5 ms with zero dropped iterations, and only begins to drop at
      // 16 000. So it is not the limit anywhere near this criterion, and any ceiling seen below that
      // belongs to the subject.
      //
      // The connection count has not been forgotten, it has moved to where it can be observed.
      // Under an open model concurrency is an **outcome** (`rate × latency`), not an input, so
      // "2 000 rps at 200 connections" is measured by offering 2 000 and reading how many VUs were
      // actually busy — `vus … max=N` in k6's own summary, which under this executor is the number
      // of requests in flight. `bench/run.sh` compares that maximum against CONNECTIONS and fails
      // the run when the service needed more concurrency than the criterion allows.
      preAllocatedVUs: Math.min(vuCeiling, 500),
      maxVUs: vuCeiling,
    },
  },
  // DECLARED BEFORE THE RUN, which is the only time a threshold means anything. `dropped_iterations`
  // has no threshold here because k6 counts it rather than rating it — the harness reads it out of
  // the summary and fails the run itself.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<250'],
    // THE CONNECTION HALF OF THE CRITERION, stated as what it actually constrains. 2 000 rps inside
    // 200 concurrent requests is arithmetically the same claim as a mean latency under 100 ms, so
    // that is what is asserted — and it is declared here, before the run, rather than read off the
    // table afterwards.
    'http_req_duration{expected_response:true}': ['avg<100'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  let response;
  if (arm === 'control') {
    // The third column: the same binary and the same engine, answering without touching the
    // database. Without it, a number that is really the generator's ceiling reads as the service's.
    response = http.get(target);
  } else {
    response = http.post(target, body, {
      headers: {
        'Content-Type': 'application/json',
        'X-Hub-Signature-256': `sha256=${signature}`,
      },
    });
  }
  check(response, { 'accepted': (r) => r.status === 200 });
}
