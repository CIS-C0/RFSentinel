// A link to a question (qa.html#detect-flock) opens its answer.
(function () {
  function openHash() {
    var el = location.hash && document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (el && el.tagName === "DETAILS") { el.open = true; el.scrollIntoView(); }
  }
  openHash();
  window.addEventListener("hashchange", openHash);
})();
// Latest release: version label and a direct link to the signed APK. Falls back to the releases page.
(function () {
  var els = document.querySelectorAll("[data-apk]");
  if (!els.length || !window.fetch) return;
  fetch("https://api.github.com/repos/CIS-C0/RFSentinel/releases/latest")
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (rel) {
      if (!rel) return;
      var apk = (rel.assets || []).filter(function (a) { return /-release\.apk$/.test(a.name); })[0];
      els.forEach(function (el) {
        if (apk) el.href = apk.browser_download_url;
        var v = el.querySelector("[data-version]");
        if (v) v.textContent = rel.tag_name;
      });
    }).catch(function () {});
})();
