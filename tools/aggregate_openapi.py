import json
import os
import time
from urllib.request import urlopen


SOURCES = {
    "User Service": ("http://user-service:8081/v3/api-docs", "http://localhost:3000"),
    "Feed Service": ("http://feed-service:8082/v3/api-docs", "http://localhost:8082"),
    "Search Service": ("http://search-service:8083/v3/api-docs", "http://localhost:8083"),
    "Notification Service": ("http://notification-service:8084/v3/api-docs", "http://localhost:8084"),
}


def fetch(url):
    for _ in range(30):
        try:
            with urlopen(url, timeout=5) as response:
                return json.load(response)
        except Exception as error:
            print(f"Waiting for {url}: {error}", flush=True)
            time.sleep(2)
    raise RuntimeError(f"Unable to fetch OpenAPI document: {url}")


def main():
    aggregate = {
        "openapi": "3.0.1",
        "info": {
            "title": "DevConnect Platform API",
            "version": "1.0.0",
            "description": "Combined OpenAPI document for User, Feed, Search and Notification services.",
        },
        "paths": {},
        "components": {"schemas": {}, "responses": {}, "parameters": {}, "requestBodies": {}},
        "tags": [],
    }

    for name, (source_url, browser_server) in SOURCES.items():
        document = fetch(source_url)
        for path, operations in document.get("paths", {}).items():
            for operation in operations.values():
                if isinstance(operation, dict):
                    operation.setdefault("tags", [name])
                    operation["servers"] = [{"url": browser_server}]
            aggregate["paths"].setdefault(path, {}).update(operations)
        for tag in document.get("tags", []):
            if tag not in aggregate["tags"]:
                aggregate["tags"].append(tag)
        for section, values in document.get("components", {}).items():
            if isinstance(values, dict):
                aggregate["components"].setdefault(section, {}).update(values)

    output = os.environ.get("OPENAPI_OUTPUT", "/out/openapi.json")
    with open(output, "w", encoding="utf-8") as target:
        json.dump(aggregate, target, ensure_ascii=False, indent=2)
    print(f"Wrote {len(aggregate['paths'])} paths to {output}", flush=True)


if __name__ == "__main__":
    main()
