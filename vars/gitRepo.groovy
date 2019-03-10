#!/usr/bin/env groovy

def name() {
    url = build.environment.get("GIT_URL")
    substring(0, url.lastIndexOf("/")).replaceAll('.git', '')
}

def url() {
    build.environment.get("GIT_URL")
}
