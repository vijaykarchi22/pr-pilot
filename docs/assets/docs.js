/* PR Pilot docs – site-wide JS */

// ── Active sidebar link via IntersectionObserver ─────────────────
(function () {
  const sidebarLinks = document.querySelectorAll('.doc-sidebar a[href^="#"]');
  if (!sidebarLinks.length) return;

  const headings = Array.from(
    document.querySelectorAll('.doc-content h2[id], .doc-content h3[id]')
  );

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          sidebarLinks.forEach((l) => l.classList.remove('active'));
          const active = document.querySelector(
            `.doc-sidebar a[href="#${entry.target.id}"]`
          );
          if (active) active.classList.add('active');
        }
      });
    },
    { rootMargin: '-10% 0px -80% 0px' }
  );

  headings.forEach((h) => observer.observe(h));
})();

// ── Mobile nav toggle ─────────────────────────────────────────────
(function () {
  const toggle = document.getElementById('nav-toggle');
  const links  = document.querySelector('.nav-links');
  if (!toggle || !links) return;
  toggle.addEventListener('click', () => {
    links.classList.toggle('open');
  });
})();

// ── Platform Switcher ─────────────────────────────────────────────
(function () {
  const PREF_KEY = 'prpilot-platform';
  const DEFAULT  = 'intellij';

  function getPref() {
    try { return localStorage.getItem(PREF_KEY) || DEFAULT; } catch { return DEFAULT; }
  }
  function setPref(p) {
    try { localStorage.setItem(PREF_KEY, p); } catch {}
  }
  function apply(plat) {
    document.body.classList.remove('platform-intellij', 'platform-vscode');
    document.body.classList.add('platform-' + plat);
    document.querySelectorAll('.plat-btn').forEach(function (btn) {
      btn.classList.toggle('active', btn.dataset.plat === plat);
    });
  }

  // Apply saved preference immediately (before paint where possible)
  apply(getPref());

  document.querySelectorAll('.plat-btn').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var plat = btn.dataset.plat;
      setPref(plat);
      apply(plat);
    });
  });
})();

