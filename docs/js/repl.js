/* Live jolt REPL for the home page.
 *
 * jolt-web.js is jolt itself — kernel, compiler, and clojure.core — compiled
 * to JavaScript by the Gambit backend (gsc -target js). The page pushes
 * source strings onto joltQueue; a Scheme green thread inside the bundle
 * polls it and calls joltOut with results. JS never calls into Scheme.
 *
 * The bundle is large (~32 MB, ~5 MB compressed) and loads as a deferred
 * script; until it's ready the terminal shows the static example.
 */
(function () {
  var out = document.getElementById('jolt-repl-out');
  var input = document.getElementById('jolt-repl-in');
  var status = document.getElementById('jolt-repl-status');
  var form = document.getElementById('jolt-repl-form');
  if (!out || !input || !form) return;

  var DEMO = '(->> (range 10) (filter even?) (map #(* % %)) (reduce +))';
  var demoDone = false;

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
      input.placeholder = '(+ 1 2)';
      // replay the static example live, then hand the prompt over
      line('user=> ' + DEMO, 'repl-in');
      globalThis.joltQueue.push(DEMO);
      return;
    }
    if (kind === 'result') { line(text, 'repl-out'); }
    else { line(text, 'repl-err'); }
    if (!demoDone) { demoDone = true; input.focus({ preventScroll: true }); }
  };

  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var src = input.value.trim();
    if (!src) return;
    line('user=> ' + src, 'repl-in');
    globalThis.joltQueue.push(src);
    input.value = '';
  });

  // the bundle itself loads via a defer tag after this script
  status.textContent = 'loading runtime…';
})();
