package com.wan.downloadmanager.identity;

import java.net.URI;

/** Opens a URL in the user's system browser (never an embedded web view: RFC 8252 §8.12). */
@FunctionalInterface
public interface BrowserLauncher {
    void open(URI uri) throws Exception;
}
