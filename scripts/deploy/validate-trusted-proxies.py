#!/usr/bin/env python3
import ipaddress
import sys


def fail(message: str) -> None:
    raise SystemExit(f"trusted proxy contract failed: {message}")


if len(sys.argv) != 3:
    fail("usage: validate-trusted-proxies.py <frontend-host-cidr> <trusted-cidrs>")

frontend_raw, trusted_raw = sys.argv[1:]


def parse_host_cidr(raw: str):
    value = raw.strip()
    if not value or "/" not in value:
        fail("every entry must be an explicit CIDR")
    try:
        network = ipaddress.ip_network(value, strict=True)
    except ValueError:
        fail("every entry must be a canonical IPv4 or IPv6 CIDR")
    if network.prefixlen != network.max_prefixlen:
        fail("only exact /32 or /128 proxy addresses are allowed")
    return network


frontend = parse_host_cidr(frontend_raw)
entries = [parse_host_cidr(raw) for raw in trusted_raw.split(",")]

if len(entries) != len(set(entries)):
    fail("duplicate proxy entries are not allowed")
if entries.count(frontend) != 1:
    fail("the environment frontend address must appear exactly once")
if len(entries) < 2:
    fail("at least one explicit Sophos proxy address is required")

print("Trusted proxy contract passed.")
