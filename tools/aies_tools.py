#!/usr/bin/env python3
"""AIES v0.4: explicitly invoked, local-only browser and diagram tools."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
from urllib.parse import urlparse

ROOT = Path(os.environ.get('AIES_WORKSPACE', os.getcwd())).resolve()
ARTIFACTS = ROOT / '.aies' / 'artifacts'

def fail(message):
    raise ValueError(message)

def inside(relative, must_exist=False):
    candidate = (ROOT / relative).resolve()
    if candidate != ROOT and ROOT not in candidate.parents:
        fail('Path outside workspace')
    if must_exist and not candidate.is_file():
        fail('Input file does not exist')
    return candidate

def ensure_artifact(relative):
    target = inside(relative)
    if target != ARTIFACTS and ARTIFACTS not in target.parents:
        fail('Output must be under .aies/artifacts/')
    target.parent.mkdir(parents=True, exist_ok=True)
    return target

def validate_local_url(url):
    parsed = urlparse(url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password:
        fail('Only HTTP(S) URLs without credentials are allowed')
    if parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
        fail('Only explicit localhost URLs are allowed in v0.4')
    # Explicit loopback; prevent DNS indirection and remote browsing.
    return url

def diagram(args):
    source = inside(args.source, must_exist=True)
    if source.suffix.lower() not in ('.puml', '.plantuml'):
        fail('Diagram source must be .puml or .plantuml')
    output = ensure_artifact(args.output)
    if output.suffix.lower() not in ('.svg', '.png'):
        fail('Diagram output must be SVG or PNG')
    if shutil.which('plantuml') is None:
        fail('plantuml not installed; see README')
    # PlantUML can include local files or remote URLs; do not execute untrusted input.
    text = source.read_text(encoding='utf-8')
    lowered = text.lower()
    for forbidden in ('!include', '!import', '!pragma', '!function', '!procedure', '!unquoted', '!theme'):
        if forbidden in lowered:
            fail('Disallowed PlantUML directive: ' + forbidden)
    fmt = '-tsvg' if output.suffix.lower() == '.svg' else '-tpng'
    proc = subprocess.run(['plantuml', '-nometadata', fmt, '-pipe'], input=text.encode(),
                          capture_output=True, timeout=40, cwd=str(ROOT))
    content = proc.stdout
    stderr = proc.stderr.decode(errors='replace')
    if proc.returncode != 0 or not content:
        fail('PlantUML failed: ' + stderr[-1200:])
    output.write_bytes(content)
    return {'ok': True, 'tool': 'diagram.render', 'artifact': str(output.relative_to(ROOT))}

def browser(args):
    validate_local_url(args.url)
    output = ensure_artifact(args.output)
    if output.suffix.lower() != '.png':
        fail('Browser screenshot must be PNG')
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        fail('Playwright missing; run scripts/setup-fedora.sh')
    with sync_playwright() as p:
        b = p.chromium.launch(headless=True)
        try:
            page = b.new_page(viewport={'width': args.width, 'height': args.height},
                              device_scale_factor=1, accept_downloads=False)
            # Prevent redirects, third-party assets, and network egress.
            def guard(route):
                req_url = route.request.url
                try:
                    validate_local_url(req_url)
                    route.continue_()
                except ValueError:
                    route.abort()
            page.route('**/*', guard)
            response = page.goto(args.url, wait_until='domcontentloaded', timeout=15000)
            page.screenshot(path=str(output), full_page=True, animations='disabled', timeout=15000)
            result = {'ok': True, 'tool': 'browser.inspect', 'url': page.url,
                      'status': response.status if response else None,
                      'title': page.title()[:300],
                      'artifact': str(output.relative_to(ROOT)),
                      'headings': page.locator('h1,h2,h3').all_text_contents()[:30],
                      'buttons': page.get_by_role('button').all_text_contents()[:30],
                      'images_without_alt': page.locator('img:not([alt])').count(),
                      'horizontal_overflow': page.evaluate('document.documentElement.scrollWidth > window.innerWidth')}
            return result
        finally:
            b.close()

def main():
    parser = argparse.ArgumentParser(description='AIES local tool runner')
    subs = parser.add_subparsers(dest='tool', required=True)
    d = subs.add_parser('diagram')
    d.add_argument('--source', required=True)
    d.add_argument('--output', default='.aies/artifacts/architecture.svg')
    b = subs.add_parser('browser')
    b.add_argument('--url', required=True)
    b.add_argument('--output', default='.aies/artifacts/browser.png')
    b.add_argument('--width', type=int, default=1440)
    b.add_argument('--height', type=int, default=900)
    args = parser.parse_args()
    try:
        if args.tool == 'browser' and not (320 <= args.width <= 2560 and 320 <= args.height <= 1600):
            fail('Viewport out of bounds')
        result = diagram(args) if args.tool == 'diagram' else browser(args)
    except (ValueError, subprocess.TimeoutExpired, OSError, Exception) as exc:
        result = {'ok': False, 'tool': args.tool, 'error': str(exc)}
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['ok'] else 1

if __name__ == '__main__':
    sys.exit(main())
