#!/usr/bin/env python3
"""
Traffic generator for the AI ticket triage service. Fills the Grafana dashboard.

Ticket mix (weights are configurable):
  new        template with random numbers   -> RAG + Claude (or a semantic hit)
  duplicate  exact resend of an earlier one -> Redis exact-cache hit
  paraphrase hand-written reworded ticket   -> semantic-cache hit once its topic is cached

Examples:
  python3 scripts/traffic.py                          60 tickets at 1/sec (about 1 minute)
  python3 scripts/traffic.py --count 300 --rate 1     steady 5-minute run, good for smooth graphs
  python3 scripts/traffic.py --count 100 --rate 0     burst: all at once, shows Kafka consumer lag
  python3 scripts/traffic.py --resolve 0.3            also resolve 30% of routed tickets at the end

Each cache miss calls Claude, which costs a fraction of a cent.
"""
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"

# (category hint, subject, body template). {n} and {amt} are filled with random numbers
TEMPLATES = [
    ("BILLING", "Charged twice", "I was billed twice for order {n} and need one charge of ${amt} reversed."),
    ("BILLING", "Refund not received", "I cancelled order {n} two weeks ago and the ${amt} refund has not arrived."),
    ("BILLING", "Wrong invoice amount", "Invoice {n} shows ${amt} but my plan costs less than that."),
    ("ACCOUNT", "Cannot log in", "I reset my password for account {n} but still get an invalid credentials error."),
    ("ACCOUNT", "Suspicious activity", "Account {n} shows a login from another country and I did not do it."),
    ("ACCOUNT", "Update email", "Please change the email on account {n} to my new address."),
    ("TECHNICAL", "API returning 500", "Our API calls fail with 500 errors since this morning, request id {n}, and customers cannot check out."),
    ("TECHNICAL", "App crashes on startup", "The mobile app closes right after the splash screen on build {n}."),
    ("TECHNICAL", "Webhooks delayed", "Webhook events for endpoint {n} arrive about 30 minutes late."),
    ("SHIPPING", "Package never arrived", "Order {n} is marked delivered but nothing is at my door."),
    ("SHIPPING", "Damaged item", "Order {n} arrived with a crushed box and a broken product."),
    ("SHIPPING", "Change delivery address", "I entered the wrong address on order {n}, can you update it?"),
    ("GENERAL", "Student discount", "Do you offer a discount for students? Reference {n}."),
    ("GENERAL", "Support hours", "What are your support hours? Asking about case {n}."),
    ("GENERAL", "Feature request", "Please add a dark mode, request {n}."),
]

# Reworded versions of the topics above, with no numbers, so they match by meaning only
PARAPHRASES = [
    ("Double charge on my card", "My card was charged two times for the same plan, please refund one."),
    ("Locked out of my account", "I changed my password but the site still says my login is invalid."),
    ("Server errors in production", "Everything on our API is returning errors and our shoppers cannot pay."),
    ("Where is my parcel", "Tracking says it was delivered but I never received anything."),
    ("Any student pricing?", "Is there a cheaper plan for people who are still in school?"),
    ("Unauthorized login", "Someone logged into my account and I do not recognize it."),
    ("Broken item received", "The package was smashed in transit and the product does not work."),
    ("Refund still pending", "I was promised my money back last month and nothing has shown up."),
]


def http(method, path, body=None):
    """Sends a request and returns (status_code, text), even for 4xx/5xx."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def send(ticket):
    """Posts one (subject, body) ticket. Returns its id, or None if it failed."""
    subject, body = ticket
    try:
        code, text = http("POST", "/tickets", {"subject": subject, "body": body})
        return json.loads(text)["id"] if code == 202 else None
    except Exception:
        return None


def cache_counters():
    """Reads cumulative cache counters (since app start) from the Prometheus endpoint."""
    _, text = http("GET", "/actuator/prometheus")
    out = {}
    for line in text.splitlines():
        if line.startswith("triage_cache_requests_total{"):
            result = line.split('result="')[1].split('"')[0]
            out[result] = int(float(line.rsplit(" ", 1)[1]))
    return out


def build_plan(count, dup, para):
    """Decides the kind of each ticket up front. Duplicates only reuse tickets planned earlier."""
    plan, originals = [], []
    for _ in range(count):
        r = random.random()
        if r < dup and originals:
            plan.append(("duplicate", random.choice(originals)))
        elif r < dup + para:
            plan.append(("paraphrase", random.choice(PARAPHRASES)))
        else:
            _, subject, body = random.choice(TEMPLATES)
            ticket = (subject, body.format(n=random.randint(10000, 99999), amt=random.randint(10, 400)))
            originals.append(ticket)
            plan.append(("new", ticket))
    return plan


def main():
    ap = argparse.ArgumentParser(description="Generate ticket traffic for the triage service")
    ap.add_argument("--count", type=int, default=60, help="number of tickets (default 60)")
    ap.add_argument("--rate", type=float, default=1.0, help="tickets per second; 0 sends everything at once")
    ap.add_argument("--dup", type=float, default=0.25, help="share of exact duplicates (default 0.25)")
    ap.add_argument("--para", type=float, default=0.25, help="share of paraphrases (default 0.25)")
    ap.add_argument("--resolve", type=float, default=0.0, help="share of routed tickets to resolve at the end")
    ap.add_argument("--seed", type=int, help="random seed for repeatable runs")
    args = ap.parse_args()
    if args.seed is not None:
        random.seed(args.seed)

    try:
        code, _ = http("GET", "/actuator/health")
    except urllib.error.URLError:
        print("Cannot reach http://localhost:8080. Is the app running?")
        sys.exit(2)
    if code != 200:
        print(f"Health check returned HTTP {code}; is the app healthy?")
        sys.exit(2)

    before = cache_counters()
    plan = build_plan(args.count, args.dup, args.para)
    print(f"Sending {len(plan)} tickets {dict(Counter(kind for kind, _ in plan))} "
          f"{'as a burst' if args.rate <= 0 else f'at {args.rate}/sec'}")

    start = time.time()
    if args.rate <= 0:
        # Burst mode: parallel posts so tickets pile up in Kafka (watch the lag panel).
        # Duplicates may race their originals here, so expect fewer exact hits
        with ThreadPoolExecutor(max_workers=10) as pool:
            ids = list(pool.map(send, [t for _, t in plan]))
    else:
        ids = []
        for i, (_, t) in enumerate(plan, 1):
            ids.append(send(t))
            if i % 10 == 0:
                print(f"  sent {i}/{len(plan)}")
            time.sleep(1.0 / args.rate)
    ok_ids = [i for i in ids if i is not None]
    print(f"Submitted {len(ok_ids)} of {len(plan)} in {time.time() - start:.0f}s. Waiting for processing...")

    # Poll until every ticket is ROUTED or FAILED (3 minute cap)
    pending, final = set(ok_ids), {}
    deadline = time.time() + 180
    while pending and time.time() < deadline:
        for tid in list(pending):
            code, text = http("GET", f"/tickets/{tid}")
            if code == 200:
                t = json.loads(text)
                if t["status"] in ("ROUTED", "FAILED"):
                    final[tid] = t
                    pending.discard(tid)
        if pending:
            time.sleep(1)

    if args.resolve > 0:
        routed = [tid for tid, t in final.items() if t["status"] == "ROUTED"]
        for tid in random.sample(routed, int(len(routed) * args.resolve)):
            http("POST", f"/tickets/{tid}/resolve")

    after = cache_counters()
    print("\n--- Summary ---")
    print("Status:   ", dict(Counter(t["status"] for t in final.values())),
          f"(+{len(pending)} still pending)" if pending else "")
    print("Category: ", dict(Counter(t.get("category") for t in final.values())))
    print("Cache (this run):", {k: after.get(k, 0) - before.get(k, 0) for k in sorted(after)})
    print("Total time: %.0fs" % (time.time() - start))
    print("Give Prometheus about 30 seconds, then look at the Grafana dashboard (last 15 minutes).")


if __name__ == "__main__":
    main()
