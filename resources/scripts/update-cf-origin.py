#!/usr/bin/env python3
"""
update-cf-origin.py — Patch a CloudFront origin's DomainName in a
DistributionConfig JSON file (written by `aws cloudfront get-distribution-config`).

Usage:
    python3 update-cf-origin.py \
        --config-file /tmp/cf-config.json \
        --origin-id   ecs-moka \
        --new-ip      1.2.3.4

The file is updated in-place and a confirmation line is printed to stdout.
Exits with a non-zero code if the origin ID is not found, so the Jenkins
pipeline step fails loudly rather than silently applying no change.
"""
import argparse
import json
import sys


def main():
    parser = argparse.ArgumentParser(description="Update a CloudFront origin domain.")
    parser.add_argument("--config-file", required=True, help="Path to DistributionConfig JSON")
    parser.add_argument("--origin-id",   required=True, help="CloudFront origin ID to update")
    parser.add_argument("--new-ip",      required=True, help="New IP address for the origin")
    args = parser.parse_args()

    with open(args.config_file) as f:
        config = json.load(f)

    updated = False
    # CloudFront distributions can have multiple origins.
    # We only patch the one that matches the provided origin ID, leaving others untouched.
    for origin in config.get("Origins", {}).get("Items", []):
        if origin.get("Id") == args.origin_id:
            origin["DomainName"] = args.new_ip
            updated = True
            break

    if not updated:
        # Fail loudly — a silent no-op here would leave CloudFront pointing at the
        # wrong IP, which is harder to debug than an explicit pipeline failure.
        print(
            f"ERROR: origin '{args.origin_id}' not found in distribution config.",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(args.config_file, "w") as f:
        json.dump(config, f)

    print(f"Updated origin '{args.origin_id}' → '{args.new_ip}'")


if __name__ == "__main__":
    main()
