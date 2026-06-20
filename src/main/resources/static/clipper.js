/* Backbrowse clipper — loaded by the bookmarklet, runs on the source page */
(function () {
  'use strict';

  // Derive the app base URL from the script's own src so the bookmarklet
  // does not need to hard-code an origin.
  var _appBase = (function () {
    try {
      var src = document.currentScript && document.currentScript.src;
      return src ? new URL(src).origin : 'http://localhost:8080';
    } catch (_) {
      return 'http://localhost:8080';
    }
  }());

  function resolveUrl(src) {
    try {
      return new URL(src, window.location.href).href;
    } catch (_) {
      return null;
    }
  }

  function getMeta(nameOrProp) {
    try {
      var el = document.querySelector('meta[name="' + nameOrProp + '"]') ||
               document.querySelector('meta[property="' + nameOrProp + '"]');
      return el ? (el.getAttribute('content') || '').trim() : '';
    } catch (_) {
      return '';
    }
  }

  function collectKeywords() {
    var seen = Object.create(null);
    var keywords = [];

    function add(raw) {
      if (!raw) return;
      String(raw).split(/[,;]+/).forEach(function (part) {
        var k = part.trim().toLowerCase();
        if (k && k.length <= 100 && !seen[k]) { seen[k] = true; keywords.push(k); }
      });
    }

    // Classic meta keyword tags
    add(getMeta('keywords'));
    add(getMeta('news_keywords'));
    add(getMeta('category'));
    add(getMeta('tags'));

    // Open Graph article tags / section (article:tag can repeat)
    add(getMeta('article:section'));
    try {
      document.querySelectorAll('meta[property="article:tag"]').forEach(function (el) {
        add(el.getAttribute('content') || '');
      });
    } catch (_) {}

    // WordPress / standard HTML rel="tag" links (Baeldung, most WP sites).
    // ~= matches "tag" as a word within space-separated rel values like "post tag".
    try {
      document.querySelectorAll('a[rel~="tag"]').forEach(function (el) {
        add(el.textContent || '');
      });
    } catch (_) {}

    // Breadcrumb navigation — covers Amazon, most e-commerce/news sites that
    // skip meta keywords but do expose a category path in the nav.
    try {
      var bcSelectors = [
        'nav[aria-label*="breadcrumb" i] a',
        '[aria-label*="breadcrumb" i] a',
        '.breadcrumb a',
        '.breadcrumbs a',
        '#wayfinding-breadcrumbs_feature_div a',
        '[itemtype*="BreadcrumbList"] [itemprop="name"]'
      ];
      var bcSeen = false;
      for (var si = 0; si < bcSelectors.length && !bcSeen; si++) {
        var nodes = document.querySelectorAll(bcSelectors[si]);
        if (nodes.length) {
          bcSeen = true;
          nodes.forEach(function (n) { add(n.textContent || n.getAttribute('content') || ''); });
        }
      }
    } catch (_) {}

    // Schema.org JSON-LD — most modern news sites, blogs, Medium, etc.
    try {
      document.querySelectorAll('script[type="application/ld+json"]').forEach(function (el) {
        try {
          var data = JSON.parse(el.textContent || el.innerText || '');
          var items = Array.isArray(data) ? data : [data];
          items.forEach(function (item) {
            if (!item) return;
            // keywords field (string or array)
            if (item.keywords) {
              var kw = Array.isArray(item.keywords) ? item.keywords.join(',') : item.keywords;
              add(kw);
            }
            // articleSection (string or array)
            if (item.articleSection) {
              var sec = Array.isArray(item.articleSection)
                ? item.articleSection.join(',') : item.articleSection;
              add(sec);
            }
            // about[] — topic entities
            var abouts = item.about ? (Array.isArray(item.about) ? item.about : [item.about]) : [];
            abouts.forEach(function (a) { if (a && a.name) add(a.name); });
          });
        } catch (_) {}
      });
    } catch (_) {}

    return keywords.slice(0, 50);
  }

  var MIN_DIM = 300; // px — filter icons, thumbnails, and tracking pixels

  function collectImages() {
    var seen = Object.create(null);
    var candidates = [];

    function add(rawSrc, alt, kind, w, h) {
      if (!rawSrc) return;
      var abs = resolveUrl(rawSrc);
      if (!abs) return;
      if (!abs.startsWith('http://') && !abs.startsWith('https://') && !abs.startsWith('//')) return;
      if (seen[abs]) return;
      // Skip only if both known dimensions are below the threshold.
      if (w != null && h != null && w < MIN_DIM && h < MIN_DIM) return;
      seen[abs] = true;
      candidates.push({ src: abs, alt: alt || '', kind: kind, width: w || null, height: h || null });
    }

    // OG image has highest priority
    var ogImg = getMeta('og:image');
    if (ogImg) add(ogImg, '', 'og_image', null, null);

    // All <img> elements
    try {
      var imgs = document.querySelectorAll('img[src]');
      for (var i = 0; i < imgs.length; i++) {
        var el = imgs[i];
        var w = el.naturalWidth || el.width || null;
        var h = el.naturalHeight || el.height || null;
        add(el.getAttribute('src'), el.alt, 'page_image', w, h);
      }
    } catch (_) {}

    return candidates.slice(0, 30); // server will truncate to 20
  }

  function collectRelatedLinks() {
    var seen = Object.create(null);
    var links = [];

    function add(rawUrl, title) {
      if (!rawUrl) return;
      var abs;
      try { abs = new URL(String(rawUrl).trim(), window.location.href).href; } catch (_) { return; }
      if (!abs.startsWith('http://') && !abs.startsWith('https://')) return;
      if (abs === window.location.href) return;
      if (seen[abs]) return;
      seen[abs] = true;
      links.push({ url: abs, title: String(title || '').trim().substring(0, 200) });
    }

    // <link rel="related"> elements in <head>
    try {
      document.querySelectorAll('link[rel~="related"]').forEach(function (el) {
        add(el.getAttribute('href'), el.getAttribute('title') || '');
      });
    } catch (_) {}

    // <a rel="related"> anchors in the page body
    try {
      document.querySelectorAll('a[rel~="related"]').forEach(function (el) {
        add(el.getAttribute('href'), el.textContent || '');
      });
    } catch (_) {}

    // JSON-LD relatedLink field (string or array)
    try {
      document.querySelectorAll('script[type="application/ld+json"]').forEach(function (el) {
        try {
          var data = JSON.parse(el.textContent || el.innerText || '');
          var items = Array.isArray(data) ? data : [data];
          items.forEach(function (item) {
            if (!item) return;
            var rl = item.relatedLink;
            if (rl) {
              (Array.isArray(rl) ? rl : [rl]).forEach(function (u) {
                if (typeof u === 'string') add(u, '');
              });
            }
          });
        } catch (_) {}
      });
    } catch (_) {}

    return links.slice(0, 10);
  }

  function collectPageText() {
    try {
      var clone = document.body.cloneNode(true);
      clone.querySelectorAll(
        'script,style,noscript,svg,canvas,video,audio,picture,' +
        'nav,header,footer,aside,form,' +
        '[role=navigation],[role=banner],[role=complementary],[role=search],' +
        '[aria-hidden=true]'
      ).forEach(function(el) { el.remove(); });
      var text = (clone.innerText || clone.textContent || '')
        .replace(/[ \t]+/g, ' ')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
      return text.slice(0, 100000);
    } catch (_) {
      return '';
    }
  }

  function buildPayload() {
    var payload = {
      url:           window.location.href,
      title:         document.title || '',
      selectedText:  (window.getSelection ? window.getSelection().toString() : '') || '',
      canonicalUrl:  '',
      description:   getMeta('description'),
      ogTitle:       getMeta('og:title'),
      ogDescription: getMeta('og:description'),
      ogImage:       getMeta('og:image'),
      images:        collectImages(),
      keywords:      collectKeywords(),
      pageText:      collectPageText(),
      relatedLinks:  collectRelatedLinks()
    };

    try {
      var canonical = document.querySelector('link[rel="canonical"]');
      if (canonical) payload.canonicalUrl = canonical.href || '';
    } catch (_) {}

    return payload;
  }

  function run() {
    var payload = buildPayload();

    fetch(_appBase + '/clip', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify(payload)
    })
    .then(function (res) {
      if (!res.ok) throw new Error('Server returned ' + res.status);
      return res.json();
    })
    .then(function (data) {
      if (!data.composeUrl) throw new Error('Missing composeUrl in response');
      window.open(
        _appBase + data.composeUrl,
        'bb_clip',
        'width=900,height=720,scrollbars=yes,resizable=yes'
      );
    })
    .catch(function () {
      // The bookmarklet already opened the window via /clip/quick.
      // Only open a new one if that was somehow blocked.
      if (!window.__clipper_win || window.__clipper_win.closed) {
        var qs = '?url='   + encodeURIComponent(payload.url)
               + '&title=' + encodeURIComponent(payload.title || '')
               + '&text='  + encodeURIComponent(payload.selectedText || '');
        window.open(
          _appBase + '/clip/quick' + qs,
          'bb_clip',
          'width=900,height=720,scrollbars=yes,resizable=yes'
        );
      }
    });
  }

  run();
}());
