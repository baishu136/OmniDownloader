"""One-shot and REPL commands for OmniDownloader's existing REST API."""
import json
import shlex

import click

from cli_anything.omnidownloader.core.tasks import get_task, task_path, wait_task
from cli_anything.omnidownloader.utils.omnidownloader_backend import Backend, BackendError


def emit(ctx, value):
    click.echo(json.dumps(value, ensure_ascii=False, indent=None if ctx.obj["json"] else 2))


def nonempty(ctx, param, value):
    if value is not None and not value.strip():
        raise click.BadParameter("must not be empty")
    return value.strip() if value is not None else None


@click.group(invoke_without_command=True)
@click.option("--server", envvar="OMNI_SERVER", default="http://127.0.0.1:58000", show_default=True)
@click.option("--timeout", type=click.FloatRange(min=0, min_open=True), default=30.0, show_default=True, help="HTTP timeout in seconds.")
@click.option("--json", "json_output", is_flag=True, help="Machine-readable JSON; put global options before the command.")
@click.option("--dry-run", is_flag=True, help="Print mutations without submitting them or writing output files.")
@click.version_option("1.0.0")
@click.pass_context
def cli(ctx, server, timeout, json_output, dry_run):
    """Control a running OmniDownloader Windows/Web backend."""
    backend = Backend(server, timeout, dry_run)
    ctx.call_on_close(backend.close)
    ctx.obj = {"backend": backend, "json": json_output}
    if ctx.invoked_subcommand is None:
        ctx.invoke(repl)


@cli.command()
@click.pass_context
def status(ctx):
    """Check the real server's client status."""
    emit(ctx, ctx.obj["backend"].request("GET", "/api/system/client-status"))


@cli.command()
@click.argument("url", callback=nonempty)
@click.pass_context
def analyze(ctx, url):
    """Resolve media metadata, formats and collection episodes."""
    # Analysis POST is read-only, so it remains available during dry-run.
    emit(ctx, ctx.obj["backend"].request("POST", "/api/analyze", {"url": url}, read_only=True))


@cli.command()
@click.argument("url", callback=nonempty)
@click.option("--title", required=True, callback=nonempty)
@click.option("--type", "download_type", type=click.Choice(["VIDEO_WITH_AUDIO", "VIDEO_ONLY", "AUDIO_ONLY", "GIF", "COVER"]), default="VIDEO_WITH_AUDIO", show_default=True)
@click.option("--resolution", default="", help="A formatId, resolutionLabel or direct media URL accepted by the backend.")
@click.option("--audio-format", type=click.Choice(["mp3", "m4a", "flac", "opus"]), default="mp3")
@click.option("--thumbnail", default="", help="Thumbnail URL; needed for COVER.")
@click.option("--author", default="")
@click.pass_context
def download(ctx, url, title, download_type, resolution, audio_format, thumbnail, author):
    """Submit one asynchronous task; use tasks wait then tasks fetch."""
    if download_type == "COVER" and not thumbnail.strip():
        raise click.UsageError("COVER requires --thumbnail from analyze metadata")
    item = {"url": url, "title": title, "downloadType": download_type,
            "selectedResolution": resolution, "audioFormat": audio_format,
            "thumbnailUrl": thumbnail, "author": author}
    emit(ctx, ctx.obj["backend"].request("POST", "/api/download", {"tasks": [item]}))


@cli.group()
def tasks():
    """Inspect, wait, cancel, remove and retrieve download tasks."""


@tasks.command("list")
@click.pass_context
def task_list(ctx):
    """List all backend tasks."""
    emit(ctx, ctx.obj["backend"].request("GET", "/api/tasks"))


@tasks.command("info")
@click.argument("task_id", callback=nonempty)
@click.pass_context
def task_info(ctx, task_id):
    """Inspect one task, including progress and localFilePath."""
    emit(ctx, get_task(ctx.obj["backend"], task_id))


@tasks.command("wait")
@click.argument("task_id", callback=nonempty)
@click.option("--wait-timeout", type=click.FloatRange(min=0, min_open=True), default=300.0)
@click.option("--interval", type=click.FloatRange(min=0, min_open=True), default=1.0)
@click.pass_context
def task_wait(ctx, task_id, wait_timeout, interval):
    """Poll until COMPLETED; failures and timeouts return nonzero."""
    emit(ctx, wait_task(ctx.obj["backend"], task_id, wait_timeout, interval))


@tasks.command("cancel")
@click.argument("task_id", callback=nonempty)
@click.pass_context
def task_cancel(ctx, task_id):
    """Cancel an existing task."""
    backend = ctx.obj["backend"]
    get_task(backend, task_id)
    emit(ctx, backend.request("POST", task_path(task_id) + "/cancel"))


@tasks.command("delete")
@click.argument("task_id", callback=nonempty)
@click.option("--delete-file", is_flag=True, help="Also delete the server's downloaded file.")
@click.pass_context
def task_delete(ctx, task_id, delete_file):
    """Remove one existing task record."""
    backend = ctx.obj["backend"]
    get_task(backend, task_id)
    emit(ctx, backend.request("DELETE", task_path(task_id), params={"deleteFile": str(delete_file).lower()}))


@tasks.command("clear")
@click.option("--all", "clear_all", is_flag=True, help="Cancel active tasks before clearing all records.")
@click.pass_context
def task_clear(ctx, clear_all):
    """Clear finished records, or cancel and clear all records."""
    backend = ctx.obj["backend"]
    operations = []
    if clear_all:
        for task in backend.request("GET", "/api/tasks"):
            if task["status"] not in ("COMPLETED", "FAILED", "CANCELLED"):
                operations.append(backend.request("POST", task_path(task["id"]) + "/cancel"))
    result = backend.request("POST", "/api/tasks/clear", params={"clearAll": str(clear_all).lower()})
    emit(ctx, {"dryRun": True, "operations": operations + [result]} if backend.dry_run else result)


@tasks.command("fetch")
@click.argument("task_id", callback=nonempty)
@click.option("--output", required=True, type=click.Path(dir_okay=False))
@click.pass_context
def task_fetch(ctx, task_id, output):
    """Stream a completed file to this machine without overwriting."""
    backend = ctx.obj["backend"]
    task = get_task(backend, task_id)
    if task["status"] != "COMPLETED":
        raise BackendError("Task is not COMPLETED; run tasks wait first")
    emit(ctx, backend.fetch(task_path(task_id) + "/download", output))


@cli.group()
def settings():
    """Inspect or update server settings. Cookie values are redacted."""


@settings.command("show")
@click.pass_context
def settings_show(ctx):
    """Read settings with cookies redacted."""
    emit(ctx, ctx.obj["backend"].request("GET", "/api/settings"))


@settings.command("set")
@click.option("--download-dir", type=click.Path(file_okay=False), help="Path on the SERVER machine.")
@click.option("--proxy-url", help="Proxy for media requests; empty string clears it.")
@click.option("--cookie-file", type=click.Path(exists=True, dir_okay=False), help="UTF-8 file containing the Bilibili cookie; never echoed.")
@click.option("--max-concurrent", type=click.IntRange(1, 32), help="Saved for the next backend restart.")
@click.option("--theme", type=click.Choice(["auto", "light", "dark"]))
@click.pass_context
def settings_set(ctx, download_dir, proxy_url, cookie_file, max_concurrent, theme):
    """Update only supplied settings."""
    payload = {k: v for k, v in {"downloadDir": download_dir, "proxyUrl": proxy_url,
              "maxConcurrentTasks": max_concurrent, "theme": theme}.items() if v is not None}
    if download_dir is not None and not download_dir.strip():
        raise click.UsageError("download-dir must not be empty")
    if cookie_file:
        with open(cookie_file, encoding="utf-8-sig") as handle:
            payload["bilibiliCookie"] = handle.read().strip()
    if not payload:
        raise click.UsageError("Supply at least one setting")
    emit(ctx, ctx.obj["backend"].request("POST", "/api/settings", payload))


@cli.group()
def relay():
    """Manage the original server's relay site configuration."""


@relay.command("list")
@click.pass_context
def relay_list(ctx):
    """List configured relay sites."""
    emit(ctx, ctx.obj["backend"].request("GET", "/api/relay-sites"))


@relay.command("add")
@click.argument("name", callback=nonempty)
@click.argument("url", callback=nonempty)
@click.pass_context
def relay_add(ctx, name, url):
    """Add a named relay site."""
    emit(ctx, ctx.obj["backend"].request("POST", "/api/relay-sites", {"name": name, "url": url}))


@relay.command("delete")
@click.argument("site_id", callback=nonempty)
@click.pass_context
def relay_delete(ctx, site_id):
    """Remove a relay site by ID."""
    from urllib.parse import quote
    emit(ctx, ctx.obj["backend"].request("DELETE", "/api/relay-sites/" + quote(site_id, safe="")))


@relay.command("title")
@click.argument("url", callback=nonempty)
@click.pass_context
def relay_title(ctx, url):
    """Ask the backend to fetch a relay page title."""
    emit(ctx, ctx.obj["backend"].request("GET", "/api/relay-sites/fetch-title", params={"url": url}))


@cli.command()
@click.pass_context
def repl(ctx):
    """Interactive shell. Global options are inherited; exit/quit ends it."""
    from cli_anything.omnidownloader.utils.repl_skin import ReplSkin
    skin = ReplSkin("omnidownloader", version="1.0.0")
    skin.print_banner()
    session = skin.create_prompt_session()
    backend = ctx.obj["backend"]
    inherited = ["--server", backend.server, "--timeout", str(backend.timeout)]
    if ctx.obj["json"]:
        inherited.append("--json")
    if backend.dry_run:
        inherited.append("--dry-run")
    while True:
        try:
            line = skin.get_input(session).strip()
            if line in ("exit", "quit"):
                break
            if not line:
                continue
            args = shlex.split(line)
            if args[0] == "repl":
                skin.error("Already in REPL")
                continue
            main(inherited + (["--help"] if args == ["help"] else args), standalone_mode=False)
        except EOFError:
            break
        except KeyboardInterrupt:
            continue
        except (click.ClickException, BackendError, ValueError, OSError) as exc:
            skin.error(str(exc))
    skin.print_goodbye()


def main(args=None, standalone_mode=True):
    import sys
    actual = list(sys.argv[1:] if args is None else args)
    try:
        return cli.main(args=actual, standalone_mode=False)
    except (BackendError, click.ClickException, OSError, ValueError) as exc:
        if not standalone_mode:
            raise
        code = exc.exit_code if isinstance(exc, click.ClickException) else 1
        if "--json" in actual:
            click.echo(json.dumps({"error": str(exc)}, ensure_ascii=False), err=True)
        else:
            click.echo(f"Error: {exc}", err=True)
        raise SystemExit(code) from exc
    except click.Abort:
        if not standalone_mode:
            raise
        click.echo("Aborted", err=True)
        raise SystemExit(130)


if __name__ == "__main__":
    main()
