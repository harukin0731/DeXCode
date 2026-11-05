# DeXCode

DeXCode is a small Kotlin Android client for code-server, OpenVSCode Server, and SSH-accessible development machines. It keeps the editor UI on the remote server and adds Android-native connection, window, and URL handling.

## Implemented

- Save multiple named `http(s)` connections in `SharedPreferences`.
- Save SSH workspace connections with host, port, username, workspace path, remote server port, optional private key, and an optional remote command prefix.
- Always open the launcher after the app task is killed; the launcher can bring active editor/port tasks to the front.
- Record connection usage automatically and show the most recently used connections first.
- Always open the connection launcher after the app task is killed; the launcher lists active editor/port tasks and can bring any of them to the front.
- Persistent Android WebView profile: cookies, DOM storage, IndexedDB, cache, service-worker data, and WebView session state are not cleared by the app.
- Resizable activities and Android 15+ multi-instance system UI declaration.
- Compact 40dp toolbars on the launcher, editor, SSH, and port windows.
- `New Window` starts another editor activity in a new document/task, suitable for separate DeX windows; same-origin VS Code `window.open` requests are promoted to the same real window type, while authentication popups remain in-app.
- JavaScript, WebSocket-compatible WebView settings, DOM storage, file upload, popup WebViews, and authenticated downloads.
- Native Android clipboard fallback for WebView pages where browser `navigator.clipboard` is unavailable, scoped to the active connection origin.
- `Open Port / URL` with `localhost:PORT` mapping to code-server's `/proxy/PORT/` route.
- Port windows provide editable full URLs (including query parameters) and an optional Developer mode with request, navigation, HTTP-error, and JavaScript-console logs plus WebView remote debugging.
- Open a forwarded URL in a separate in-app WebView task or the external browser.
- Load VS Code Tunnel URLs directly in the app WebView and retain authentication popups as in-app child WebViews.
- SSH connections use an Android JSch client, persist accepted host keys in the app's private storage, and support private-key, password, passphrase, and keyboard-interactive prompts.
- After SSH login, the app reuses an HTTP server on the configured remote loopback port, or finds `code-server`/`openvscode-server`; when neither exists it installs code-server in the remote user's home directory using the standalone installer, starts it on `127.0.0.1` with Workspace Trust disabled, and creates an SSH local forward to the WebView.
- SSH-started VS Code servers receive the configured `extensionsGallery` equivalent through `EXTENSIONS_GALLERY`, so the Microsoft Marketplace endpoints are applied without editing the versioned remote `product.json`.
- SSH workspace paths accept absolute paths, `~`, and paths relative to the remote user's home. The resolved path is sent as the Web workspace folder even when an existing remote server is reused; missing paths fail explicitly instead of silently opening the home directory.
- SSH workspace windows can create additional local forwards for remote development ports from `Open Port / URL`.

## SSH/Tailscale setup

1. Install and connect the Tailscale Android app on the phone/tablet and sign in to the same tailnet as the target machine.
2. Make sure the target accepts SSH connections. Use its Tailscale IP or MagicDNS name in the connection form. Ubuntu and macOS can use their normal SSH server; WSL with its own `sshd` can also be entered directly.
3. Select `SSH workspace`, enter the Linux/macOS username, workspace path, and an unused remote server port such as `8080`.
4. For Tailscale SSH, a private key is normally unnecessary because Tailscale authenticates the client node; leave the key empty first. For a traditional SSH server over Tailscale, select the private key if required. Without a selected key, password and keyboard-interactive authentication remain available; the optional Remember setting encrypts the password with Android Keystore. A selected private key uses key-only authentication and requests its own passphrase only when encrypted.
5. For Windows OpenSSH forwarding into WSL, set the optional command prefix to something like `wsl.exe -d Ubuntu --`. WSL running its own SSH server does not need the prefix.

If a Tailscale SSH connection rejects the no-auth method, Tailscale documents the compatibility form of adding the literal `+password` to the username (for example `ubuntu+password`) and entering any password in the prompt.

On the first connection, the app shows the SSH host-key prompt and stores an accepted key locally. The remote VS Code server is bound to loopback and reached only through the authenticated SSH tunnel. The remote process is deliberately left running after the Android window closes so the next connection can reuse it; it can be stopped from the remote machine in the normal way.

This is a practical VS Code Web bridge, not a reimplementation of the VS Code Remote-SSH protocol. It does not install the official VS Code Server or run VS Code's remote extension host directly. It provides the same useful bootstrap shape for this client—SSH login, remote server setup, and a secure tunnel—while the editor is served by code-server/OpenVSCode Server.

## Settings export and live forwarding status

- In the Connections screen, use the menu **Export settings** to save a versioned JSON file through Android's document picker. It includes saved connections, the last-used connection, port-window developer mode and SSH known hosts. Saved passwords, private-key contents and WebView login/session storage are excluded. Key URIs are references only; another device must reselect its own key files. This action exports a file and does not import or overwrite settings.
- Active windows now come from Activity lifecycle events rather than recent-task history. The visible Connections screen refreshes window/SSH-forward state every second and on state-change notifications, including when it is visible but unfocused in DeX. Only changed sections are rebuilt. Main-frame load errors remain visible instead of being replaced by a generic loaded status.
- In an SSH workspace, **Open Port / URL → Copy address** creates the local TCP listener and copies its URL without opening another WebView or browser. Repeating the same port choice reuses its current listener. Connections lists the active forwards with Copy/Stop actions; the workspace's own editor forward is protected from accidental stopping.
- Forwards belong to their SSH workspace window, remain alive while it is backgrounded, and end when that window closes, its SSH session disconnects or Android terminates the process. There is no foreground service. SSH keepalives now run every five seconds with two retries to detect a silent broken connection sooner; the UI reflects the disconnect after JSch detects it.
- Direct code-server connections can copy `/proxy/<port>/` URLs, including a bare numeric port such as `3000`. These are labeled as server-provided proxy URLs, not active Android SSH listeners. Removing a proxy URL from the list does not disable the server's proxy route. vscode.dev tunnels must use VS Code's Ports panel and its generated URL; code-server proxy routes are not synthesized for them.

## Build

Open the folder in Android Studio and run the `app` debug configuration, or run:

```bash
./gradlew assembleDebug
```

The project targets SDK 35, uses Java/Kotlin 17, and has a minimum SDK of 26. The Gradle Wrapper is included. The verification command `./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug` succeeds; lint reports warnings and no errors.

## Port forwarding decision

For direct Web URL connections, code-server already exposes forwarded services through `/proxy/<port>/` and can also use a custom `VSCODE_PROXY_URI` or `--proxy-domain`. When a remote VS Code page emits `http://localhost:5173`, DeXCode rewrites it to the current server's `/proxy/5173/` URL.

For SSH connections, DeXCode uses an actual SSH local forward. The WebView receives `http://127.0.0.1:<local-port>/`, while the SSH session forwards that port to `127.0.0.1:<remote-port>` on the remote device. A clicked remote `localhost:PORT` URL can be forwarded on demand, and the same conversion is available from `Open Port / URL`.

OpenVSCode Server does not provide the same built-in forwarding contract in the sources reviewed for this MVP, so it should be deployed behind SSH, a reverse proxy, or another server-side forwarding layer. A future adapter can add a per-connection URL template if an OpenVSCode deployment uses a known proxy.

## Important manifest choices

`android:resizeableActivity="true"` is set on the application and all relevant activities. `android:documentLaunchMode="always"`, standard launch modes, and `FLAG_ACTIVITY_MULTIPLE_TASK`/`FLAG_ACTIVITY_NEW_TASK` allow each editor or port view to become a separate task/window. Android 15's `android.window.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI` is also declared.

The MVP sets `android:usesCleartextTraffic="true"` so LAN/Tailscale HTTP code-server URLs work. This is intentionally broad for the first development build; production should move to HTTPS or replace this with a more constrained networking policy once the set of allowed hosts is known.

## Not implemented yet

- The official VS Code Remote-SSH protocol and its matching VS Code Server/remote extension host.
- Tailscale login/control from inside the app. Tailscale must be connected by the Android Tailscale app; DeXCode only uses the resulting network path.
- Automatic port discovery/listing from the VS Code Ports panel.
- A per-connection certificate/trust policy.
- Android-side Node.js or a local Extension Host.
- A foreground service for keeping an SSH tunnel alive after its editor window is backgrounded or removed.
- A dedicated user-facing download manager screen.

## Device checks

1. Register an HTTP and an HTTPS code-server connection and verify login survives force-stop/relaunch.
2. Install a Web Extension and verify its state after relaunch.
3. On Samsung DeX, resize one editor window and use `New Window` twice; open different connections in each.
4. Start a remote Vite/Next/Expo server and verify both `/proxy/<port>/` and a clicked `localhost:PORT` link.
5. Verify Ctrl/Alt/Shift/Meta, function keys, Escape, Tab, right-click, wheel, drag, and VS Code shortcuts in a physical keyboard/mouse setup.
6. Test file upload, a server download, popup links, and `Open in browser` on the target Android/WebView version.
7. With Tailscale connected, create SSH connections for Ubuntu, macOS, WSL with its own `sshd`, and Windows OpenSSH + WSL using the command prefix; verify first-connect host-key approval and key/passphrase prompts.

## Fixed Android forwarding ports

In an SSH workspace, open **Open Port / URL**, enter the remote port (for example `3000`) and the Android port (for example `13000`). Copy address starts the forward at `http://127.0.0.1:13000/`; opening a browser is optional. Leave Android port blank for automatic allocation.

**Remember for this SSH connection** saves the mapping and restores it after SSH reconnects. These settings are also included in settings exports. An occupied or unavailable fixed port produces an error without substituting another port. Restoration failures are listed in Open Port / URL and do not prevent the editor from connecting. Different SSH windows share Android's port space, so choose distinct local ports when running them together.

To forget a fixed mapping, enter its remote port, clear Android port, keep Remember checked, and start/copy the forward. Existing listeners remain active until stopped or disconnected; Stop ends a listener but retains its saved mapping for the next connection. An explicitly chosen additional local port can coexist with an existing forward to the same remote port. Forward lifetime still follows the owning SSH workspace window.

## Desktop workspace header

Web and SSH editor windows share a compact header with tabs for currently open editor workspaces, a + button for a new window, a file-search button (workbench Quick Open; long press for commands), and connection status. A grid button opens Connections. Tabs focus existing Android tasks; they do not reload editors or duplicate SSH sessions. VS Code's own file tabs stay inside the editor. Status tooltips expose the current page/connection phase; clicking status opens forwarding and workspace controls. Existing overflow menus and Back navigation remain available.

On Android 15+ the app requests a transparent caption background, uses reported caption button bounds to find a safe horizontal area, and reserves interactive regions for tabs and controls. System minimize/maximize/close controls remain system owned; an unclaimed strip remains for dragging. If the system does not expose button bounds, or the safe space is narrower than 360dp, the header stays below the system caption. Older Android versions keep the in-app header. Resizing recomputes the placement. Samsung DeX caption behavior and command-palette activation require validation on a real device; no private Samsung APIs are used.

### Header layout and shortcuts

The updated header defaults to a dark surface, with distinct outlined active tabs, full-name tooltips, an all-workspaces dropdown, a new-window + button and a bordered file-search field. Same-name windows get numbered labels. + opens another window of the current connection; the grid button opens Connections. Clicking search opens Quick Open, and long-pressing opens the command palette. Narrow windows use a search icon and compact status dot, while keeping these controls available. A reserved blank strip remains for dragging.

Click the status dot/label for the full connection phase, forward count, forwarding dialog, command palette, terminal toggle, reconnect/reload, close-current-workspace and a persistent Dark header toggle. Reconnect on SSH reconnects the SSH session; on Web connections it reloads the page. Dark/light header preference applies across editor windows. Editor actions verify the workbench control appeared and show a failure message if unavailable; a login page does not receive these actions. Actual DeX layout, contrast and pointer/touch behavior still require device verification.

### Unified menu and verified editor controls

The status indicator and rightmost menu button now open the same workspace menu. It contains Editor (file search, command palette, terminal), Connection / ports (forwarding, SSH reconnect, page reload, Connections), Windows (new/switch/back/close), header appearance, settings and Web connection diagnostics. The separate Activity overflow menus were removed; SSH reconnect and page reload remain distinct operations.

Android-only key injection was replaced with page-local workbench actions. File search/command palette first click the VS Code command-center control if available, then set the Quick Input field to file-search or command mode. If unavailable, DOM keyboard events with VS Code-compatible key/legacy numeric fields provide a fallback. The UI checks that the Quick Input widget appeared; terminal actions check for a visibility change. Unavailable actions display a message rather than silently succeeding. This uses no remote extension, server change or new native JavaScript interface. A different VS Code DOM version, custom keybindings or a not-yet-ready editor can still require the built-in VS Code menu.

Run `node app/src/test/js/editor-actions.test.cjs` for seven page-control regressions using a mock DOM, including no-op/hidden-widget rejection and mode changes. These are not a real Chromium or device integration test.

### Merge the original VS Code titlebar

The app now mirrors visible VS Code titlebar buttons (navigation, layout/panel toggles, application menus and other titlebar actions) into the native header. Search stays in the existing native search field. Wide windows show a horizontally scrollable control strip; all detected controls are also available under **VS Code titlebar controls** in the unified menu, including in narrow windows. Labels/tooltips and enabled states come from VS Code itself.

**Integrate VS Code titlebar** is enabled by default and can be switched off from the native menu at any time. The original DOM and buttons stay alive. A measured, topmost titlebar is clipped out of the native WebView frame; the actual WebView viewport is enlarged by the same number of pixels so VS Code relayouts normally and its bottom status bar is retained. File tabs, the activity bar and editor content are not targeted. No remote settings or CSS grid are changed. Unsupported/non-workbench pages retain their whole viewport. The preference is included in JSON exports.

The bridge refreshes with the existing visible-window status refresh. DOM differences, web zoom and DeX resizing still require real-device verification. Run `node app/src/test/js/titlebar-controls.test.cjs` for seven mock-DOM checks of proxy calls, state/IDs, command-center filtering and crop guards.
