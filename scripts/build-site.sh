#!/usr/bin/env bash
# Assemble the static site into ./_site (docs viewer, recorded demo, run guide).
# Only the documents listed in site/docs.json are published; local.env files never are.
set -euo pipefail
cd "$(dirname "$0")/.."
out=_site
rm -rf "$out"; mkdir -p "$out/docs/md" "$out/run" "$out/assets"
cp -r site/index.html site/site.css site/docs.json site/demo site/vendor "$out/"
cp site/docs/index.html "$out/docs/index.html"
for id in $(python3 -c "import json;print(' '.join(i['id'] for g in json.load(open('site/docs.json')) for i in g['items']))"); do
  cp "docs/$id.md" "$out/docs/md/$id.md"
done
cp run.html "$out/run/index.html"
cp docs/assets/banner.svg docs/assets/claude-loves-udogg.svg "$out/assets/"
touch "$out/.nojekyll"
echo "built $out: $(find "$out" -type f | wc -l) files, $(du -sh "$out" | cut -f1)"
