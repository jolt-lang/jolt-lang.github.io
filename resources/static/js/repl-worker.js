/* Runs the jolt runtime off the main thread.
 *
 * Booting jolt-web.js is seconds of straight-line work — it brings up the
 * kernel, the compiler and clojure.core — so on the main thread it freezes
 * the page no matter when it is started. It runs here instead and talks to
 * the page over postMessage.
 *
 * The bundle's host interface is two globals: it shifts source strings off
 * joltQueue and calls joltOut(kind, text) with the results.
 */

// Gambit decides it's in a browser by testing `this === this.window`, and in
// that mode parks main on a DOMContentLoaded listener. A worker has neither,
// and without them the runtime takes the nodejs path and calls require(), so
// stand up just enough of both.
self.window = self;
self.document = {
  addEventListener: function (_type, fn) { setTimeout(fn, 0); }
};

self.joltQueue = [];
self.joltOut = function (kind, text) {
  self.postMessage({ kind: kind, text: text });
};

self.onmessage = function (ev) {
  self.joltQueue.push(ev.data);
};

self.importScripts('/js/jolt-web.js');
