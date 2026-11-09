# Third-party components

- FFmpeg 7.1 essentials (Gyan build), built with `--enable-gpl --enable-version3`. GPL v3 text is included in `licenses/FFmpeg-GPL-3.txt`. Binary provenance: https://github.com/GyanD/codexffmpeg/releases/tag/7.1 ; upstream source revision: https://github.com/FFmpeg/FFmpeg/commit/b08d7969c5 . Runtime archive and executable hashes are pinned in `runtime-lock.json`.
- Deno 2.9.8, MIT. License included in `licenses/Deno-MIT.txt`. Source: https://github.com/denoland/deno/tree/v2.9.8 .
- Font Awesome Free 6.4.0, license included with the vendored web assets in `_internal/web/static/vendor/fontawesome/`.
- yt-dlp, yt-dlp-ejs, FastAPI, uvicorn, pywebview and other Python dependency versions are recorded in `requirements-lock.txt`; their installed license/metadata files accompany the frozen dependencies where supplied by the packages.

OmniDownloader application and build source accompany this release. Native libraries and third-party code retain their respective licenses.

- Brand cloud artwork: Google Material Icons Outlined, Apache-2.0; source SVG, hashes and license in `shared/icons/material` in the corresponding source package.
