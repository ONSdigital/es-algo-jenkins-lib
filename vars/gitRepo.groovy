#!/usr/bin/env groovy

def name(url) {
    url.substring(url.lastIndexOf("/")+1,url.lastIndexOf("."))
}
