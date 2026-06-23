# jolt-lang.github.io

Website and documentation for [Jolt](https://github.com/jolt-lang/jolt), a
Clojure implementation on Chez Scheme. A small Clojure static-site generator
(Selmer templates + markdown-clj) builds the site into `docs/`, which GitHub
Pages serves.

## Build

```bash
lein run build        # generate the site into docs/
lein run              # build + serve at http://localhost:3000
lein run 8080         # serve on a different port
```

## Layout

```
resources/
  templates/      Selmer templates (base, home, docs, 404)
  md/             documentation pages, one Markdown file each
  docpages.edn    doc registry: [filename title type], also drives the nav
  static/         css, js, img — copied verbatim into docs/
src/site/         the generator (core.clj builds, util.clj parses Markdown + TOC)
```

To add a documentation page, drop a Markdown file in `resources/md/` and add a
row to `resources/docpages.edn`. The landing page lives in
`resources/templates/home.html`; shared styling is in
`resources/static/css/screen.css`.

## Publishing

`.github/workflows/deploy.yml` runs on every push to `main`: it builds the site
and commits the regenerated `docs/`. GitHub Pages is configured to serve from
the `main` branch `/docs` folder.
