/* Live jolt REPL for the home page.
 *
 * jolt-web.js is jolt itself — kernel, compiler, and clojure.core — compiled
 * to JavaScript by the Gambit backend (gsc -target js). The page pushes
 * source strings onto joltQueue; a Scheme green thread inside the bundle
 * polls it and calls joltOut with results. JS never calls into Scheme.
 *
 * The bundle is large (~28 MB raw, ~3 MB gzipped) and booting it takes
 * seconds of uninterrupted work, so it runs in a worker (see repl-worker.js)
 * and the two globals above become postMessage in each direction. Until it's
 * ready the terminal shows the static example.
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
  var send; // hands a source string to the runtime

  function line(text, cls) {
    var el = document.createElement('div');
    if (cls) el.className = cls;
    el.textContent = text;
    out.appendChild(el);
    out.scrollTop = out.scrollHeight;
  }

  function receive(kind, text) {
    if (kind === 'ready') {
      out.textContent = '';
      status.textContent = 'ready';
      status.className = 'repl-ready';
      input.disabled = false;
      input.placeholder = 'type an expression';
      // replay the static example live, then hand the prompt over
      line('user=> ' + DEMO, 'repl-in');
      send(DEMO);
      return;
    }
    if (kind === 'out') { line(text.replace(/\n+$/, ''), 'repl-stdout'); return; }
    line(text, kind === 'result' ? 'repl-out' : 'repl-err');
    if (!demoDone) {
      demoDone = true;
      line('Tab fills an example · ↑ recalls history', 'repl-hint');
      input.focus({ preventScroll: true });
    }
  }

  function failed() {
    status.textContent = 'runtime failed to load';
  }

  function startWorker() {
    var worker = new Worker('/js/repl-worker.js');
    worker.onmessage = function (ev) { receive(ev.data.kind, ev.data.text); };
    worker.onerror = failed;
    send = function (src) { worker.postMessage(src); };
  }

  // No worker: run the bundle inline instead. Same REPL, but the page locks
  // up for a few seconds while it boots, so hold it until everything else has
  // loaded. Gambit parks main on DOMContentLoaded, which has long since fired
  // by then, so re-dispatch it once the bundle has evaluated.
  function startInline() {
    globalThis.joltQueue = [];
    globalThis.joltOut = receive;
    send = function (src) { globalThis.joltQueue.push(src); };

    var runtime = document.createElement('script');
    runtime.src = '/js/jolt-web.js';
    runtime.onerror = failed;
    runtime.onload = function () {
      document.dispatchEvent(new Event('DOMContentLoaded'));
    };
    document.body.appendChild(runtime);
  }

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
    send(src);
    history.push(src);
    histPos = -1;
    input.value = '';
  });

  status.textContent = 'loading runtime…';

  try {
    if (typeof Worker !== 'function') throw new Error('workers unsupported');
    startWorker();
  } catch (e) {
    if (document.readyState === 'complete') startInline();
    else window.addEventListener('load', startInline);
  }
})();
