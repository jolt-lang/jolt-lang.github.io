/* Live jolt REPL for the home page.
 *
 * jolt-web.js is jolt itself — kernel, compiler, and clojure.core — compiled
 * to JavaScript by the Gambit backend (gsc -target js). The page pushes
 * source strings onto joltQueue; a Scheme green thread inside the bundle
 * polls it and calls joltOut with results. JS never calls into Scheme.
 *
 * The bundle is large (~32 MB raw, ~3.8 MB gzipped) and is injected only
 * after the page has loaded — a preload link in the page head starts the
 * download early without blocking rendering. Until it's ready the terminal
 * shows the static example.
 */
(function () {
  var out = document.getElementById('jolt-repl-out');
  var input = document.getElementById('jolt-repl-in');
  var status = document.getElementById('jolt-repl-status');
  var form = document.getElementById('jolt-repl-form');
  if (!out || !input || !form) return;

  var DEMO = '(->> (range 10) (filter even?) (map #(* % %)) (reduce +))';
  var EXAMPLE = '(+ 1 2)';
  var demoDone = false;
  var history = [];
  var histPos = -1; // -1 = not browsing history

  function line(text, cls) {
    var el = document.createElement('div');
    if (cls) el.className = cls;
    el.textContent = text;
    out.appendChild(el);
    out.scrollTop = out.scrollHeight;
  }

  globalThis.joltQueue = [];
  globalThis.joltOut = function (kind, text) {
    if (kind === 'ready') {
      out.textContent = '';
      status.textContent = 'ready';
      status.className = 'repl-ready';
      input.disabled = false;
      input.placeholder = 'type an expression';
      // replay the static example live, then hand the prompt over
      line('user=> ' + DEMO, 'repl-in');
      globalThis.joltQueue.push(DEMO);
      return;
    }
    if (kind === 'out') { line(text.replace(/\n+$/, ''), 'repl-stdout'); return; }
    line(text, kind === 'result' ? 'repl-out' : 'repl-err');
    if (!demoDone) {
      demoDone = true;
      line('Tab fills an example · ↑ recalls history', 'repl-hint');
      input.focus({ preventScroll: true });
    }
  };

  input.addEventListener('keydown', function (ev) {
    if (ev.key === 'Tab' && !input.value) {
      ev.preventDefault();
      input.value = EXAMPLE;
      return;
    }
    if (ev.key === 'ArrowUp' && history.length) {
      ev.preventDefault();
      histPos = histPos < 0 ? history.length - 1 : Math.max(0, histPos - 1);
      input.value = history[histPos];
      return;
    }
    if (ev.key === 'ArrowDown' && histPos >= 0) {
      ev.preventDefault();
      histPos += 1;
      if (histPos >= history.length) {
        histPos = -1;
        input.value = '';
      } else {
        input.value = history[histPos];
      }
    }
  });

  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var src = input.value.trim();
    if (!src) return;
    line('user=> ' + src, 'repl-in');
    globalThis.joltQueue.push(src);
    history.push(src);
    histPos = -1;
    input.value = '';
  });

  status.textContent = 'loading runtime…';

  // Inject jolt-web.js only after the page has fully loaded. Executing the
  // ~26 MB bundle during initial load froze the page for seconds; starting it
  // post-load leaves rendering and interaction untouched (the preload link in
  // the page head has usually fetched it by then).
  function loadRuntime() {
    var runtime = document.createElement('script');
    runtime.src = '/js/jolt-web.js';
    runtime.onerror = function () {
      status.textContent = 'runtime failed to load';
    };
    // Gambit's web runtime doesn't run main directly: it parks it on a
    // DOMContentLoaded listener. That already fired long before we injected
    // the script, so the listener would never run and the REPL would sit at
    // 'loading runtime…' forever. Re-dispatch the event once the bundle has
    // evaluated. It doesn't bubble, so only document-level listeners see it —
    // the runtime's is the only one.
    runtime.onload = function () {
      document.dispatchEvent(new Event('DOMContentLoaded'));
    };
    document.body.appendChild(runtime);
  }

  if (document.readyState === 'complete') loadRuntime();
  else window.addEventListener('load', loadRuntime);
})();
