import os
import subprocess
import json

# GITHUB_REPOSITORY is set by Actions; default keeps local runs on upstream.
REPO_NAME = os.environ.get("GITHUB_REPOSITORY", "dawn-used-yeet-alt/aniyomi-extensions")

def run_gh(*args: str, success_errors: tuple[str, ...] = ()) -> str | None:
    result = subprocess.run(
        ["gh", *args],
        capture_output=True,
        encoding="utf-8",
        check=False,
    )
    print(f"Calling gh with args {list(args)}")
    print(f"Result: {result.stdout.strip()}")
    print(f"Result (err): {result.stderr.strip()}")
    if result.returncode == 0:
        return result.stdout.strip()

    error = result.stderr.lower()
    if any(success_error in error for success_error in success_errors):
        return result.stdout.strip()

    return None

def get_referenced_assets() -> set[str]:
    index = json.loads(
        run_gh(
            "api",
            "--header",
            "Accept: application/vnd.github.raw+json",
            f"repos/{REPO_NAME}/contents/index.json?ref=repo",
        )
    )
    return {
        extension["resources"]["apkUrl"]
        for extension in index["extensionList"]["extensions"]
    }
