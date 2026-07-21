#!/usr/bin/env python3
"""Validate local documentation links and selected source-of-truth references."""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
DOCS_DIRECTORY = ROOT / "docs"
OPENAPI = ROOT / "src/main/resources/openapi/stock-analyst-v1.json"
COMPOSE = ROOT / "docker-compose.yml"
BUILD_GRADLE = ROOT / "build.gradle.kts"
PYTHON_VERSION = ROOT / ".python-version"
CI_WORKFLOW = ROOT / ".github/workflows/ci-build.yml"
BACKEND_PROVIDER_MODULE = (
    ROOT / "src/main/kotlin/net/bobinski/stockanalyst/BackendProviderModule.kt"
)

EXPECTED_MARKDOWN_FILES = {
    "README.md",
    "docs/api-semantics.md",
    "docs/architecture.md",
    "docs/deployment.md",
    "docs/development.md",
    "docs/operations.md",
}

REQUIRED_RELATIVE_REFERENCES = {
    "README.md": {
        "src/main/resources/openapi/stock-analyst-v1.json",
        "docs/api-semantics.md",
        "docs/architecture.md",
        "docs/deployment.md",
        "docs/development.md",
        "docs/operations.md",
    },
    "docs/api-semantics.md": {
        "../src/main/resources/openapi/stock-analyst-v1.json",
    },
    "docs/architecture.md": {
        "../src/main/resources/openapi/stock-analyst-v1.json",
        "deployment.md",
        "operations.md",
    },
    "docs/deployment.md": {
        "../docker-compose.yml",
        "../src/main/resources/openapi/stock-analyst-v1.json",
        "development.md",
    },
    "docs/development.md": {
        "../.python-version",
        "../src/main/resources/openapi/stock-analyst-v1.json",
        "deployment.md",
    },
    "docs/operations.md": {
        "../.github/workflows/live-canary.yml",
        "../backend-yfinance/app.py",
        "../docker-compose.yml",
        "../src/main/kotlin/net/bobinski/stockanalyst/BackendProviderModule.kt",
        "deployment.md",
    },
}

LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)")
HEADING_PATTERN = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$")
CONFIG_VARIABLE_PATTERN = re.compile(r"\b(?:BACKEND_URL|YFINANCE_[A-Z0-9_]+)\b")
PRIVATE_DEPLOYMENT_DOMAIN_PATTERN = re.compile(r"\bbobinski\.net\b", re.IGNORECASE)
ADAPTER_DEFAULT_PATTERN = re.compile(
    r"^\s+(YFINANCE_[A-Z0-9_]+):\s+"
    r"\$\{(YFINANCE_[A-Z0-9_]+):-(\d+)\}\s*$",
    re.MULTILINE,
)


def markdown_files() -> list[Path]:
    return [README, *sorted(DOCS_DIRECTORY.glob("*.md"))]


def repository_path(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def github_anchor(text: str) -> str:
    without_tags = re.sub(r"<[^>]+>", "", text)
    without_markup = without_tags.replace("`", "").replace("*", "").replace("_", "")
    normalized = without_markup.strip().lower()
    normalized = re.sub(r"[^\w\- ]", "", normalized)
    return re.sub(r"\s+", "-", normalized)


def anchors(path: Path) -> set[str]:
    seen: dict[str, int] = defaultdict(int)
    result: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = HEADING_PATTERN.match(line)
        if not match:
            continue
        base = github_anchor(match.group(1))
        duplicate = seen[base]
        seen[base] += 1
        result.add(base if duplicate == 0 else f"{base}-{duplicate}")
    return result


def markdown_targets(path: Path) -> list[tuple[int, str]]:
    targets: list[tuple[int, str]] = []
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        targets.extend((line_number, match.group(1)) for match in LINK_PATTERN.finditer(line))
    return targets


def validate_file_set(errors: list[str]) -> list[Path]:
    files = markdown_files()
    actual = {repository_path(path) for path in files}
    missing = EXPECTED_MARKDOWN_FILES - actual
    unexpected = actual - EXPECTED_MARKDOWN_FILES
    if missing:
        errors.append(f"missing required Markdown files: {', '.join(sorted(missing))}")
    if unexpected:
        errors.append(
            "new top-level documentation must be added to the validation manifest: "
            + ", ".join(sorted(unexpected))
        )
    return files


def validate_links(files: list[Path], errors: list[str]) -> int:
    anchor_cache: dict[Path, set[str]] = {}
    relative_link_count = 0

    for source in files:
        for line_number, raw_target in markdown_targets(source):
            if raw_target.startswith(("http://", "https://", "mailto:")):
                continue

            relative_link_count += 1
            path_part, separator, anchor = raw_target.partition("#")
            destination = (
                source
                if not path_part
                else (source.parent / unquote(path_part)).resolve()
            )

            try:
                destination.relative_to(ROOT)
            except ValueError:
                errors.append(
                    f"{repository_path(source)}:{line_number}: "
                    f"relative link escapes the repository: {raw_target}"
                )
                continue

            if not destination.is_file():
                errors.append(
                    f"{repository_path(source)}:{line_number}: "
                    f"missing relative link target: {raw_target}"
                )
                continue

            if separator and destination.suffix.lower() == ".md":
                destination_anchors = anchor_cache.setdefault(destination, anchors(destination))
                if anchor not in destination_anchors:
                    errors.append(
                        f"{repository_path(source)}:{line_number}: "
                        f"missing Markdown anchor: {raw_target}"
                    )

    return relative_link_count


def validate_no_private_deployment_domains(
    files: list[Path],
    errors: list[str],
) -> None:
    for source in files:
        for line_number, line in enumerate(
            source.read_text(encoding="utf-8").splitlines(),
            start=1,
        ):
            if PRIVATE_DEPLOYMENT_DOMAIN_PATTERN.search(line):
                errors.append(
                    f"{repository_path(source)}:{line_number}: "
                    "public documentation must not reference private deployment domains"
                )


def validate_required_references(files: list[Path], errors: list[str]) -> None:
    files_by_name = {repository_path(path): path for path in files}
    for source_name, required_targets in REQUIRED_RELATIVE_REFERENCES.items():
        source = files_by_name.get(source_name)
        if source is None:
            continue
        actual_targets = {
            target.partition("#")[0]
            for _, target in markdown_targets(source)
            if not target.startswith(("http://", "https://", "mailto:", "#"))
        }
        for target in sorted(required_targets - actual_targets):
            errors.append(f"{source_name}: missing required source reference: {target}")


def validate_openapi(errors: list[str]) -> int:
    try:
        contract = json.loads(OPENAPI.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"cannot parse canonical OpenAPI: {error}")
        return 0

    if contract.get("openapi") != "3.0.3":
        errors.append("canonical OpenAPI must remain version 3.0.3")

    paths = contract.get("paths")
    if not isinstance(paths, dict):
        errors.append("canonical OpenAPI does not contain a paths object")
        return 0

    versioned_paths = sorted(path for path in paths if path.startswith("/v1/"))
    readme = README.read_text(encoding="utf-8")
    for path in versioned_paths:
        if f"`{path}`" not in readme:
            errors.append(f"README API summary is missing canonical path: {path}")
    return len(versioned_paths)


def validate_configuration_docs(errors: list[str]) -> int:
    compose = COMPOSE.read_text(encoding="utf-8")
    operations = (DOCS_DIRECTORY / "operations.md").read_text(encoding="utf-8")
    variables = sorted(set(CONFIG_VARIABLE_PATTERN.findall(compose)))
    for variable in variables:
        if f"`{variable}`" not in operations:
            errors.append(f"operations guide is missing Compose variable: {variable}")

    adapter_defaults = ADAPTER_DEFAULT_PATTERN.findall(compose)
    adapter_variables = {variable for variable in variables if variable.startswith("YFINANCE_")}
    parsed_adapter_variables = {key for key, _, _ in adapter_defaults}
    if parsed_adapter_variables != adapter_variables:
        missing_defaults = adapter_variables - parsed_adapter_variables
        errors.append(
            "cannot parse Compose defaults for adapter variables: "
            + ", ".join(sorted(missing_defaults))
        )
    for key, interpolation_key, default in adapter_defaults:
        if key != interpolation_key:
            errors.append(
                f"Compose variable {key} interpolates a different key: {interpolation_key}"
            )
        if f"| `{key}` | `{default}` |" not in operations:
            errors.append(
                f"operations guide has a missing or stale Compose default: {key}={default}"
            )

    compose_backend_match = re.search(r"\bBACKEND_URL=([^\s]+)", compose)
    if compose_backend_match is None:
        errors.append("cannot find the Compose BACKEND_URL override")
    elif f"`{compose_backend_match.group(1)}`" not in operations:
        errors.append(
            "operations guide does not mention the Compose BACKEND_URL override: "
            + compose_backend_match.group(1)
        )

    provider_module = BACKEND_PROVIDER_MODULE.read_text(encoding="utf-8")
    backend_default_match = re.search(
        r'System\.getenv\("BACKEND_URL"\)\s*\?:\s*"([^"]+)"',
        provider_module,
    )
    if backend_default_match is None:
        errors.append("cannot find the application BACKEND_URL default")
    elif (
        f"| `BACKEND_URL` | `{backend_default_match.group(1)}` |"
        not in operations
    ):
        errors.append(
            "operations guide has a missing or stale application BACKEND_URL default: "
            + backend_default_match.group(1)
        )

    python_version = PYTHON_VERSION.read_text(encoding="utf-8").strip()
    development = (DOCS_DIRECTORY / "development.md").read_text(encoding="utf-8")
    if python_version not in development:
        errors.append(
            "development guide does not mention the version from .python-version: "
            + python_version
        )

    build_gradle = BUILD_GRADLE.read_text(encoding="utf-8")
    toolchain_match = re.search(r"jvmToolchain\((\d+)\)", build_gradle)
    if toolchain_match is None:
        errors.append("cannot find jvmToolchain version in build.gradle.kts")
    elif f"JDK {toolchain_match.group(1)}" not in development:
        errors.append(
            "development guide does not mention the configured JDK toolchain: "
            + toolchain_match.group(1)
        )

    workflow = CI_WORKFLOW.read_text(encoding="utf-8")
    if "python3 scripts/validate-docs.py" not in workflow:
        errors.append("CI workflow does not run scripts/validate-docs.py")

    return len(variables)


def main() -> int:
    errors: list[str] = []
    files = validate_file_set(errors)
    validate_no_private_deployment_domains(files, errors)
    relative_links = validate_links(files, errors)
    validate_required_references(files, errors)
    openapi_paths = validate_openapi(errors)
    configuration_variables = validate_configuration_docs(errors)

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(
        "Documentation validation passed: "
        f"{len(files)} Markdown files, "
        f"{relative_links} relative links, "
        f"{openapi_paths} versioned OpenAPI paths, "
        f"{configuration_variables} Compose variables."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
