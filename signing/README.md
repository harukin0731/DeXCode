# Local signing key

`debug.keystore` is the original DeXCode debug signing key. It stays in this
directory locally and is excluded from Git along with other signing material.
Restore this same file from your private backup on another development machine
to produce APKs that can update existing installations with the original signature.

Original certificate SHA-256:

```text
D2:0A:69:CC:DC:55:6E:38:F9:9B:27:83:A1:6E:9E:79:D0:14:8A:09:DD:72:38:85:C7:AE:18:72:DA:B8:AF:C1
```

Check the restored key with:

```bash
keytool -list -keystore signing/debug.keystore -alias androiddebugkey
```

The synthetic SSH key fixtures under `app/src/test/resources/ssh/` are unrelated
test data and are intentionally kept in Git. They must never be authorized on
any real SSH server.
