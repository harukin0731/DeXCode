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

## Browser downloads and agent text copying

HTTP/HTTPS downloads now validate the scheme and catch request/queue errors. Android 10+ saves to public Downloads; Android 8/9 retain app-scoped Downloads without broad storage permission. Cookie/User-Agent headers are retained. Web/SSH/forwarded windows and their popups share this handling. Failure to enqueue is reported rather than escaping the download listener.

Browser-local `blob:`/`data:` downloads use a page-local fetch, bounded 48 KiB chunks, and an ordered native file transfer. A completed transfer opens Android's document picker so the user chooses where to save. Partial/error transfers are deleted and never offered as complete. Filenames are sanitized; each browser-local file is limited to 128 MiB, with at most three unfinished transfers per window. Keep the source window open until saving finishes; cancellation removes the temporary file. Downloads blocked by CSP, unavailable blob URLs or cross-origin frames can still fail explicitly. HTTP DownloadManager jobs continue independently of the window.

Clipboard hooks now install at document start where supported (with page-finished fallback), before extension UIs cache the API. Plain-text ClipboardItem writes, navigator.clipboard, legacy copy events and execCommand selections are supported. A source-origin-aware WebMessage listener replaces the all-frame JavascriptInterface. Native access requires the configured editor origin (or current SSH loopback origin); other frames cannot directly read native clipboard data. Workbench child frames may relay text writes through their verified embedding frame, including nested extension webviews. Clipboard reads are never relayed. Text bridging is limited to 262144 characters; non-text formats use the browser's original API. Old WebViews without WebMessageListener support retain browser clipboard behavior.

These paths target VS Code agent/terminal/extension copying, including Codex and Claude Code interfaces. No real authenticated agent session or Android device has been exercised; version-specific behavior still requires device testing. JavaScript mock tests are in `app/src/test/js/{clipboard,download}.test.cjs`; JVM tests verify filename/origin handling and exact native chunk assembly/abort behavior.

### Copy agent/terminal text when selected

**Copy agent / terminal selection automatically** defaults on in the unified header menu and can be disabled without affecting explicit Copy commands. Trusted pointer release and keyboard selection gestures trigger a short debounce; dragging, empty selections, password fields and ordinary Monaco source-editor selections do not auto-copy. Agent webview text uses the existing write-only parent relay. Canvas-rendered xterm selections are read by dispatching a copy event with an isolated DataTransfer to the terminal's own copy handler, rather than relying on window.getSelection or sending Ctrl+C. This does not change the remote terminal.integrated.copyOnSelection setting or send terminal input. The preference is exported in settings JSON. Custom TUIs that capture mouse selection without exposing xterm selection may still require their own copy command (or Shift-drag to select terminal text).

### Explorer Download temporary files

VS Code Explorer's browser export creates an object URL, clicks a temporary download anchor and schedules URL revocation (upstream: https://github.com/microsoft/vscode/blob/main/src/vs/base/browser/dom.ts, triggerDownload). The download bridge now captures the source Blob when created and retains it synchronously when the download is requested. Reading its slices no longer requires fetching an expired object URL or passing blob fetch through page CSP. Synthetic anchor clicks are intercepted directly, including detached anchors; regular user clicks are captured and HTTP anchors remain unchanged. Multiple exports are queued instead of dropped by a single busy flag. Document-finished installation backs up document-start injection on older WebViews.

Download failures now show a diagnostic dialog with a safe failure code, transfer phase, native exception class when available and WebView version. Copy diagnostics includes no workspace path, filename, file content, credentials or URL. The same 128 MiB limit remains. Tests include immediate URL revocation with blocked fetch, multiple file exports, HTTP pass-through and error classification. Device download-provider and actual Explorer-session behavior still require device verification.

### Shell selection redraw fix

A real Chromium + xterm.js 5.5.0 mouse-drag test reproduced selected terminal text with zero copy requests. xterm's DOM renderer replaced the glyph span used as the gesture target while updating selection highlighting; the detached span no longer had an `.xterm` ancestor at mouse release. Gesture tracking now retains the terminal root instead, reacquires an eligible root on release, and uses the composed event path when available. Disconnected roots are ignored. The same real-browser drag test now sends exactly the selected terminal string once. The download implementation is unchanged by this selection fix. This validates browser-side capture with a simulated native clipboard bridge; Android clipboard delivery and live agent TUIs still require device testing.

### Editor-area terminals and clipboard diagnostics

Terminal detection is based on `.xterm` before the ordinary source-editor exclusion, including terminals hosted by `.terminal-editor` in the editor area. Gesture capture now listens at window and document levels with event deduplication, plus mouse-event fallback. This handles release events consumed by workbench handlers. Terminal copy probes start at the helper textarea and bubble to the terminal, so handlers at either location can populate the copy payload. Pointer cancellation retains the stable selection context for subsequent selection-change events.

Under **Editor**, **Copy terminal selection** explicitly copies the current visible terminal selection independently of automatic-copy preference. **Clipboard diagnostics** shows safe counters, detected region, terminal counts, extraction length, browser outcome, native origin/preference checks, native write/readback result, window focus and WebView version. It includes no selected text, clipboard contents, source URL or workspace path. A failed explicit copy opens these diagnostics. This allows detection, extraction, native rejection and Android readback to be distinguished on device. Diagnostics are transient and not exported automatically.

Real Chromium+xterm testing covers reparenting a live terminal under editor/Monaco wrappers and an editor that consumes release events. These tests simulate the native bridge; they do not verify Android clipboard delivery or a real VS Code deployment.

### Terminal copying without injected shortcuts

Terminal selection copying uses the terminal copy-event payload, DOM selection and the clipboard API bridge. An earlier fallback injected Ctrl+Shift+C when no selection text was available, including after ordinary clicks. This shortcut can be interpreted as terminal input when a workbench does not consume it, so the fallback has been removed from both automatic and explicit copy paths. Empty selections now report `no_selection` without sending keys or stealing focus. Terminal selection mode and normal clipboard writes remain available.

A real Chromium test with xterm 6.0.0 confirms the previous script dispatched a synthetic Ctrl+Shift+C after a simple click, while the revised script dispatches no key events and produces no terminal input. The actual Codex CLI exit was not reproduced in this fixture; on-device confirmation remains necessary.

### TUI mouse capture and terminal text selection mode

If VS Code's own Copy also fails, a TUI may be consuming mouse events without creating an xterm selection. **Editor > Terminal text selection mode (Shift drag)** is an explicit, persisted toggle, off by default. Enable it, then select the text again. Trusted terminal mouse gestures receive Shift selection semantics, which xterm uses to override application mouse reporting. No Shift keypress or terminal input is injected. Disable the mode to use the TUI's normal mouse controls. Source-editor gestures are not modified. A no-selection diagnostic can enable this mode directly. The preference is included in settings exports.

Real xterm testing with mouse reporting enabled reproduces empty terminal selection and mouse escape sequences in normal mode. With terminal text selection mode enabled, the same mouse drag produces the selected text, one copy request and no application mouse input. This reproduces a plausible failure mechanism, not a direct inspection of the user's running TUI.

## Header icon and placement cleanup

The native header uses font-independent stroke icons with consistent 20dp artwork and 36dp controls. Left: Connections, workspace tabs/list and New window; navigation appears as a stable Back/Forward pair when space permits. The file-search field stays centered using equally sized left/right groups. Right: fixed primary-sidebar/panel/secondary-sidebar slots, a highlighted terminal-selection toggle, connection status and the workspace menu. Disabled workbench actions retain their slots; arbitrary extension actions no longer appear as truncated two-character buttons. All original workbench actions remain named entries under VS Code titlebar controls in the unified menu.

Narrow windows use a compact search icon and move navigation/panel controls to their existing named menu entries according to a single width policy; the main menu remains available. Tooltips/accessibility labels describe each control. The selection icon reflects the existing terminal-selection preference and toggles the same function as its menu checkbox. Clipboard and download implementations are unchanged. System caption buttons still reserve their own area; blank header space remains draggable. Layout budgets are tested from 240dp to 1920dp, but native DeX rendering still needs device validation.

## Existing SSH code-server management

SSH workspace menu → Connection → **Server management** inspects the existing executable, resolved installation path, installed version and recorded running version. It detects official Linux standalone prefixes, Debian/RPM packages and global npm installations. Unknown wrappers, unidentified serving processes and container deployments stay read-only. No separate DeXCode-managed installation is created, and no update runs automatically.

Use **Check latest stable**, or enter an exact stable version, then confirm the target installation. Downloaded release assets require the SHA-256 digest published in GitHub release metadata; older releases without a digest cannot be downloaded through this feature. Standalone versions already installed in the original prefix can be selected without downloading, and old version directories are retained. Package updates use the original package manager/prefix. **Previous version** is a recorded target, not a guaranteed transactional rollback; availability and package-manager rules still apply. Existing standalone configuration and user data remain outside the version directory; Debian package updates preserve existing conffiles.

Updates run as detached jobs on the host with installation and account-wide operation locks, bounded logs and a persistent status journal. Closing the window does not cancel a running update; reopen management after reconnecting to retrieve the result. Administrator operations require an explicit checkbox and noninteractive `sudo -n` permission on the host; SSH passwords are never supplied to sudo. Update failures can leave a partially changed package installation and must be inspected before retrying.

**Restart server** sends TERM only to a process recorded by this DeXCode version with a matching Linux boot ID and process start time, then reconnects using the connection's existing workspace settings. Legacy PID-only records and external service-manager processes are not stopped through this action. Other windows using the same server are affected. Reconnecting alone may reuse a running old version; external services require their normal restart procedure. If server preparation fails after SSH authentication, the SSH session remains available for management and repair.

Verification: `python3 app/src/test/scripts/server-management.test.py` runs isolated fake-installation tests without network, sudo or real package-manager changes. JVM tests cover protocol validation, digest metadata and actual generated detached-job execution. These checks do not replace Android-device testing or a live privileged package update.

## Mobile terminal inertia scrolling

Newer xterm builds synthesize `-xterm-gesturechange` during touch inertia without client coordinates. When a TUI enables SGR mouse reporting, this can emit `ESC[<65;NaN;NaNM` into terminal input. `terminal-touch.js` preserves the last single-touch position per terminal screen and supplies bounded finite coordinates for these inertia events. Events with invalid deltas or no usable anchor are stopped before reaching xterm. Real coordinate-bearing gestures and other views retain their normal behavior. Touch cancellation and multitouch invalidate the saved anchor; source editing and clipboard scripts are unchanged.

A real Chromium touch swipe with code-server 4.136.2's bundled xterm reproduced an invalid SGR report before the patch and only finite SGR reports after it. This verifies browser-side terminal data, not Android device delivery. The tracked regression fixture checks missing/valid coordinates, screen resizing, invalid deltas, cancellation, multitouch, unrelated views and duplicate installation.

## Touch toolbar paste into terminals

More → Paste now checks a dedicated paste context. The currently focused writable xterm input is accepted in both panel terminals and editor-area terminals. It invokes VS Code's Linux terminal paste shortcut (Ctrl+Shift+V), retaining VS Code's clipboard handling and multiline/bracketed-paste behavior, rather than injecting clipboard text into the shell. Normal editor/search inputs retain Ctrl+V. See the [official terminal copy/paste bindings](https://code.visualstudio.com/docs/terminal/basics#_copy-paste).

Other code-only shortcuts, copy-as-Ctrl+C and select-all remain blocked in terminal contexts. Password fields, iframes, disabled/readonly inputs and active IME composition remain guarded. Paste never redirects focus to another code editor. Real Chromium tests verify context handling against a real xterm helper textarea in both placements; Android key delivery and actual workbench paste still require device testing.

### Updating an active server

Package updates can replace the Node executable while the old server process remains alive. Linux marks that executable as ` (deleted)` in `/proc/PID/exe`. Management now retains the bundle-path association, reports that restart is required and disables another update. Bootstrap does not silently reuse a responding code-server whose executable has been replaced. This avoids treating HTTP success as proof that the old process can serve newly installed workbench files. Legacy/external processes still require their normal restart mechanism; their ownership is not inferred merely from a deleted executable.
