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
// The criterion's connection count, unless a probe deliberately asks for more.
const vuCeiling = Number(__ENV.VUS || connections);
const body = __ENV.BODY || '';
const signature = __ENV.SIGNATURE || '';

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      // THE POOL IS THE CRITERION'S CONNECTION COUNT, and this paragraph is here because that was
      // changed once — on a correct observation and a wrong conclusion — and changed back a day
      // later by the measurement below.
      //
      // THE OBSERVATION, which was right: a VU under this executor is not a connection. It is held
      // for the whole round trip, so the pool caps **requests in flight**, and the highest rate such
      // a pool can offer is `VUs / latency`. At the 380 ms the service was showing, 200 VUs cannot
      // offer more than about 526 rps — exactly the band all three arms of the pilot landed in, and
      // read at the time as a shared ceiling of unknown origin.
      //
      // THE CONCLUSION DRAWN FROM IT, which was wrong: that the pool should therefore be sized by
      // the rate. It should not. The criterion says **2 000 rps over 200 connections** — 200 is a
      // property of the offered load, not an outcome to observe — and a pool of 200 is what models
      // it. The 526 rps *was* the criterion's answer: at 200 connections and 380 ms per request, 526
      // is all that fits, and the service fails the line because its latency is 380 ms rather than
      // the 100 ms that 2 000 through 200 would require.
      //
      // The measurement that settled it (bench-b to bench-a, 2026-09-16): with the pool opened to
      // 4 000, the same service takes 4 000 concurrent requests, delivers 196 rps and fails a
      // quarter of them. That is a harder scenario than the one declared, and reporting it against
      // this criterion would be measuring something else and calling it the number.
      //
      // So `dropped_iterations` is the honest signal rather than an embarrassment: it counts the
      // offered requests that did not fit inside the allowed concurrency. What the generator *can*
      // do is not in question — unpooled, this pair offers 8 000 rps at p50 0.5 ms with none
      // dropped, and first strains at 16 000.
      //
      // VUS overrides the pool for probing what the service does with more concurrency than the
      // criterion allows. A run that sets it is not a run against this criterion, and the harness
      // records the peak so that an override left on cannot pass unnoticed.
      preAllocatedVUs: vuCeiling,
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
