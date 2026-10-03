#!/usr/bin/env python3
"""Builds the GitHub Pages website in docs/ from the page sources in site/pages/.

Each page source starts with a metadata comment:

    <!--META {"title": "...", "description": "...", "nav": "faq"} -->

followed by the page body (the part between the header and the footer). The
script wraps every page in the shared template, adds the FAQ structured data
(schema.org FAQPage) for every <details class="faq"> block, writes
docs/sitemap.xml, and fails on any broken internal link or duplicate FAQ id.

    python tools/build_site.py
"""
import datetime
import html
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "site", "pages")
OUT = os.path.join(ROOT, "docs")
BASE = "https://cis-c0.github.io/RFSentinel/"
REPO = "https://github.com/CIS-C0/RFSentinel"
RELEASES = REPO + "/releases/latest"
DISCORD = "https://discord.gg/NDTjn8HMGq"

GITHUB_SVG = ('<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1 1.8 2.8 1.3 3.5 1 0-.8.4-1.3.7-1.6-2.7-.3-5.5-1.3-5.5-5.9 0-1.3.5-2.4 1.2-3.2 0-.3-.5-1.5.2-3.2 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0c2.3-1.5 3.3-1.2 3.3-1.2.6 1.7.2 2.9.1 3.2.8.8 1.2 1.9 1.2 3.2 0 4.6-2.8 5.6-5.5 5.9.5.4.9 1.1.9 2.2v3.3c0 .3.1.7.8.6A12 12 0 0 0 12 .3"/></svg>')
DISCORD_SVG = ('<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 2.9l-.6 1.3a18.3 18.3 0 0 0-5.5 0L8.7 2.9a19.7 19.7 0 0 0-4.9 1.5C.5 9-.3 13.6.1 18.1a19.9 19.9 0 0 0 6 3l1.3-2a12.9 12.9 0 0 1-1.9-.9l.4-.3a14.2 14.2 0 0 0 12.1 0l.4.3c-.6.4-1.2.7-1.9.9l1.3 2a19.8 19.8 0 0 0 6-3c.5-5.2-.8-9.7-3.5-13.7zM8 15.3c-1.2 0-2.2-1.1-2.2-2.4S6.8 10.5 8 10.5s2.2 1.1 2.2 2.4-1 2.4-2.2 2.4zm8 0c-1.2 0-2.2-1.1-2.2-2.4s1-2.4 2.2-2.4 2.2 1.1 2.2 2.4-1 2.4-2.2 2.4z"/></svg>')
DOWNLOAD_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/></svg>'

NAV = [
    ("index", "", "Home"),
    ("how-it-works", "how-it-works.html", "How it works"),
    ("guides", "police-detector.html", "Guides"),
    ("install", "install.html", "Install"),
    ("android-auto", "android-auto.html", "Android Auto"),
    ("esp32", "esp32.html", "ESP32"),
    ("qa", "qa.html", "Q&amp;A"),
]

GUIDES = [
    ("police-detector.html", "Detect police with Android"),
    ("flock-camera-detector.html", "Flock & plate-reader cameras"),
    ("imsi-catcher-detector.html", "Stingray / IMSI catcher signs"),
    ("tracker-detector.html", "AirTag & tracker detection"),
    ("drone-detector.html", "Remote ID drone detector"),
    ("smart-glasses-detector.html", "Smart glasses detector"),
    ("sophia-alternative.html", "Free SØPHIA alternative"),
]

TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<link rel="canonical" href="{url}">
<meta name="robots" content="{robots}">
<meta name="theme-color" content="#050507">
<meta property="og:type" content="{og_type}">
<meta property="og:site_name" content="RF Sentinel">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{base}assets/og.png">
<meta property="og:image:width" content="1280">
<meta property="og:image:height" content="640">
<meta property="og:image:alt" content="RF Sentinel - BLE and WiFi police scanner for Android">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{title}">
<meta name="twitter:description" content="{description}">
<meta name="twitter:image" content="{base}assets/og.png">
<link rel="icon" href="{rel}favicon.svg" type="image/svg+xml">
<link rel="preload" href="{rel}assets/fonts/share-tech-mono.ttf" as="font" type="font/ttf" crossorigin>
<link rel="stylesheet" href="{rel}assets/style.css">
{jsonld}
</head>
<body>
<a class="skip" href="#main">Skip to content</a>
<header class="top">
  <div class="wrap">
    <a class="brand" href="{rel}./"><img src="{rel}favicon.svg" alt="" width="28" height="28">RF<span>_</span>SENTINEL</a>
    <nav class="nav" aria-label="Main">{nav}</nav>
    <div class="social">
      <a class="icon-btn discord" href="{discord}" rel="noopener">{discord_svg}<span class="lbl">Discord</span></a>
      <a class="icon-btn github" href="{repo}" rel="noopener">{github_svg}<span class="lbl">GitHub</span></a>
    </div>
  </div>
</header>
<main id="main" class="wrap">
{body}
<section class="community" aria-label="Community">
  <a class="dc" href="{discord}" rel="noopener">{discord_svg}<span><strong>Join the Discord</strong>Help, ideas, new signatures, field captures and ESP32 builds.</span></a>
  <a class="gh" href="{repo}" rel="noopener">{github_svg}<span><strong>CIS-C0/RFSentinel on GitHub</strong>Source code, releases, issues. A star helps others find it.</span></a>
</section>
</main>
<footer>
  <div class="wrap">
    <div class="cols">
      <div>
        <h4>RF Sentinel</h4>
        <ul>
          <li><a href="{releases}">Download the APK</a></li>
          <li><a href="{rel}install.html">Install &amp; update</a></li>
          <li><a href="{rel}how-it-works.html">How detection works</a></li>
          <li><a href="{rel}qa.html">Q&amp;A</a></li>
        </ul>
      </div>
      <div>
        <h4>Guides</h4>
        <ul>{guides}</ul>
      </div>
      <div>
        <h4>Community</h4>
        <ul>
          <li><a href="{discord}" rel="noopener">Discord server</a></li>
          <li><a href="{repo}" rel="noopener">GitHub repository</a></li>
          <li><a href="{repo}/issues" rel="noopener">Issues &amp; device reports</a></li>
          <li><a href="{repo}/blob/main/docs/SIGNATURES.md" rel="noopener">Signature reference</a></li>
        </ul>
      </div>
    </div>
    <p class="legal">RF Sentinel is free software under the GNU GPL v3.0. Copyright © 2026 CIS-C0.
    Receive-only: it transmits nothing, and a detection is a signature match, not proof of who is present. Check your local laws.
    Not affiliated with any company or product named on this site; names are used only to describe compatibility and detection.
    No cookies, no analytics, no trackers on this site.</p>
  </div>
</footer>
<script>
// A link to a question (qa.html#detect-flock) opens its answer.
(function () {{
  function openHash() {{
    var el = location.hash && document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (el && el.tagName === "DETAILS") {{ el.open = true; el.scrollIntoView(); }}
  }}
  openHash();
  window.addEventListener("hashchange", openHash);
}})();
// Latest release: version label and a direct link to the signed APK. Falls back to the releases page.
(function () {{
  var els = document.querySelectorAll("[data-apk]");
  if (!els.length || !window.fetch) return;
  fetch("https://api.github.com/repos/CIS-C0/RFSentinel/releases/latest")
    .then(function (r) {{ return r.ok ? r.json() : null; }})
    .then(function (rel) {{
      if (!rel) return;
      var apk = (rel.assets || []).filter(function (a) {{ return /-release\\.apk$/.test(a.name); }})[0];
      els.forEach(function (el) {{
        if (apk) el.href = apk.browser_download_url;
        var v = el.querySelector("[data-version]");
        if (v) v.textContent = rel.tag_name;
      }});
    }}).catch(function () {{}});
}})();
</script>
</body>
</html>
"""


def text_of(fragment):
    """Plain text of an HTML fragment, for structured data."""
    t = re.sub(r"<[^>]+>", " ", fragment)
    t = html.unescape(t)
    return re.sub(r"\s+", " ", t).strip()


def build():
    pages = sorted(f for f in os.listdir(SRC) if f.endswith(".html"))
    today = datetime.date.today().isoformat()
    errors = []
    sitemap = []
    names = {p for p in pages}
    for name in pages:
        raw = open(os.path.join(SRC, name), encoding="utf-8").read()
        m = re.match(r"\s*<!--META (\{.*?\}) -->\s*", raw, re.S)
        if not m:
            errors.append(f"{name}: missing META comment")
            continue
        meta = json.loads(m.group(1))
        body = raw[m.end():]
        slug = name[:-5]
        url = BASE if slug == "index" else BASE + name
        noindex = meta.get("noindex", False)
        rel = "" if slug != "404" else BASE  # 404 is served from any path

        nav = []
        for key, href, label in NAV:
            cur = ' aria-current="page"' if meta.get("nav") == key else ""
            nav.append(f'<a href="{rel}{href or "./"}"{cur}>{label}</a>')
        guides = "".join(f'<li><a href="{rel}{h}">{html.escape(t)}</a></li>' for h, t in GUIDES)

        # Structured data
        graph = []
        if slug == "index":
            graph.append({
                "@type": "SoftwareApplication",
                "name": "RF Sentinel",
                "alternateName": ["RFSentinel", "RF Sentinel police scanner"],
                "applicationCategory": "UtilitiesApplication",
                "operatingSystem": "Android 8.0+",
                "description": meta["description"],
                "url": BASE,
                "downloadUrl": RELEASES,
                "installUrl": RELEASES,
                "softwareHelp": BASE + "qa.html",
                "license": "https://www.gnu.org/licenses/gpl-3.0.html",
                "isAccessibleForFree": True,
                "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
                "codeRepository": REPO,
                "programmingLanguage": "Kotlin",
                "image": BASE + "assets/og.png",
                "screenshot": BASE + "screenshots/list-dedsec.jpg",
                "author": {"@type": "Organization", "name": "CIS-C0", "url": "https://github.com/CIS-C0"},
                "sameAs": [REPO, DISCORD],
            })
            graph.append({"@type": "WebSite", "name": "RF Sentinel", "url": BASE})
        else:
            graph.append({
                "@type": "BreadcrumbList",
                "itemListElement": [
                    {"@type": "ListItem", "position": 1, "name": "RF Sentinel", "item": BASE},
                    {"@type": "ListItem", "position": 2, "name": meta.get("crumb", meta["title"]), "item": url},
                ],
            })
        faqs = re.findall(r'<details class="faq" id="([^"]+)">\s*<summary>(.*?)</summary>\s*<div>(.*?)</div>\s*</details>', body, re.S)
        seen = set()
        for fid, _, _ in faqs:
            if fid in seen:
                errors.append(f"{name}: duplicate FAQ id {fid}")
            seen.add(fid)
        if faqs:
            graph.append({
                "@type": "FAQPage",
                "mainEntity": [{
                    "@type": "Question",
                    "name": text_of(q),
                    "acceptedAnswer": {"@type": "Answer", "text": text_of(a)},
                } for _, q, a in faqs],
            })
        jsonld = ('<script type="application/ld+json">'
                  + json.dumps({"@context": "https://schema.org", "@graph": graph}, ensure_ascii=False)
                    .replace("</", "<\\/")
                  + "</script>")

        body = (body.replace("{{RELEASES}}", RELEASES).replace("{{REPO}}", REPO)
                .replace("{{DISCORD}}", DISCORD).replace("{{DOWNLOAD_SVG}}", DOWNLOAD_SVG)
                .replace("{{DISCORD_SVG}}", DISCORD_SVG).replace("{{GITHUB_SVG}}", GITHUB_SVG))

        # Internal links must exist (pages, anchors on pages, or files in docs/).
        ids_by_page = {}
        for href in re.findall(r'(?:href|src)="([^"]+)"', body):
            if href.startswith(("http", "mailto:", "{", "data:")):
                continue
            path, _, anchor = href.partition("#")
            target = path or name
            if target in ("", "./"):
                target = "index.html"
            if target.endswith(".html") and target in names:
                if anchor:
                    tbody = ids_by_page.setdefault(target, open(os.path.join(SRC, target), encoding="utf-8").read())
                    if f'id="{anchor}"' not in tbody:
                        errors.append(f"{name}: broken anchor {href}")
            elif not os.path.exists(os.path.join(OUT, target)):
                errors.append(f"{name}: broken link {href}")

        page = TEMPLATE.format(
            title=html.escape(meta["title"]), description=html.escape(meta["description"]),
            url=url, base=BASE, rel=rel, robots="noindex" if noindex else "index, follow, max-image-preview:large",
            og_type="website" if slug == "index" else "article",
            jsonld=jsonld, nav="".join(nav), guides=guides, body=body.strip(),
            discord=DISCORD, repo=REPO, releases=RELEASES,
            discord_svg=DISCORD_SVG, github_svg=GITHUB_SVG,
        )
        open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n").write(page)
        if not noindex:
            prio = "1.0" if slug == "index" else ("0.9" if slug == "qa" else "0.8")
            sitemap.append(f"  <url><loc>{url}</loc><lastmod>{today}</lastmod><priority>{prio}</priority></url>")

    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
           + "\n".join(sitemap) + "\n</urlset>\n")
    open(os.path.join(OUT, "sitemap.xml"), "w", encoding="utf-8", newline="\n").write(xml)

    if errors:
        print("\n".join(errors))
        sys.exit(1)
    print(f"built {len(pages)} pages, {len(sitemap)} in the sitemap")


if __name__ == "__main__":
    build()
