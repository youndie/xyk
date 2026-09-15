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
const body = __ENV.BODY || '';
const signature = __ENV.SIGNATURE || '';

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      // 200 connections, and no more: the criterion names them, so the generator must not quietly
      // open a 201st to keep up. Reaching the cap is what produces `dropped_iterations`, which is
      // the honest signal rather than a hidden one.
      preAllocatedVUs: connections,
      maxVUs: connections,
    },
  },
  // DECLARED BEFORE THE RUN, which is the only time a threshold means anything. `dropped_iterations`
  // has no threshold here because k6 counts it rather than rating it — the harness reads it out of
  // the summary and fails the run itself.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<250'],
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
