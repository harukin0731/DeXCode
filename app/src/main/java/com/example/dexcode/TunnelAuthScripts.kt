package com.example.dexcode

import org.json.JSONObject

/** Transfer the callback ciphertext unchanged; VS Code owns validation and decryption. */
internal object TunnelAuthScripts {
    fun read(requestId: String): String = """
        (function() {
          const id = ${JSONObject.quote(requestId)};
          const page = new URL(window.location.href);
          if (page.protocol !== "https:" ||
              !["vscode.dev", "insiders.vscode.dev"].includes(page.hostname) ||
              page.pathname !== "/callback" ||
              page.searchParams.get("vscode-reqid") !== id) return "";
          const prefixes = ["vscode-dev.url-callbacks[", "vscode-web.url-callbacks["];
          for (const prefix of prefixes) {
            const key = prefix + id + "]";
            const value = window.localStorage.getItem(key);
            if (value !== null && value !== "") return JSON.stringify([key, value]);
          }
          return "";
        })()
    """.trimIndent()

    fun deliver(origin: String, key: String, value: String): String = """
        (function() {
          if (window.location.origin !== ${JSONObject.quote(origin)}) return false;
          const key = ${JSONObject.quote(key)};
          const value = ${JSONObject.quote(value)};
          const oldValue = window.localStorage.getItem(key);
          window.localStorage.setItem(key, value);
          window.dispatchEvent(new StorageEvent("storage", {
            key: key,
            newValue: value,
            oldValue: oldValue,
            storageArea: window.localStorage,
            url: window.location.origin + "/callback"
          }));
          return true;
        })()
    """.trimIndent()
}
