# SockoBuf

SockoBuf is a Burp Suite extension that adds a WebSocket message editor tab for protobuf payloads.
It inspects raw frames, tries to decode base64-encoded bodies, and surfaces printable UTF-8 fields
so you can read or edit structured data without exporting it to another tool.

## Features
- Detects binary protobuf frames as well as base64-wrapped payloads.
- Traverses nested messages (depth-limited) and lists printable UTF-8 leaves in a friendly editor.
- Pretty-prints JSON when running inside Proxy (read-only mode) to simplify eyeballing structured blobs.
- Allows editing from Repeater: modified text is mapped back onto the protobuf leaves and re-serialized.
- Falls back gracefully to the original bytes when parsing fails, logging errors to the Burp output tab.

## Requirements
- Burp Suite Community or Professional with Montoya API support (2023.12+ recommended).
- Java 17+ runtime (Gradle toolchain config already targets 17).

## Building
1. Install a JDK 17+ and ensure `JAVA_HOME` points to it.
2. From the repo root run:
   ```bash
   ./gradlew clean build
   ```
3. The fat JAR lands in `build/libs/sockobuf-1.0.0.jar`.

## Burp Installation
1. Open Burp → `Extender` → `Extensions` → `Add`.
2. Choose `Java` and select the JAR from `build/libs`.
3. Watch the Extender output for `[Sockobof] loaded.` (Burp displays the extension as "Sockobof").
4. When a WebSocket message looks like protobuf, Burp will show a `Protobuf` tab next to the usual editors.

## Usage Notes
- **Proxy (read-only):** The tab pretty-prints JSON-looking strings but does not allow edits, ensuring traffic
  is untouched as it flows through Proxy.
- **Repeater (editable):** Each printable UTF-8 field becomes a line (or block) you can modify. On send,
  SockoBuf re-encodes only those fields and leaves the rest of the frame intact. If the original payload
  was base64, the extension re-wraps it before handing it back to Burp.

## Limitations
- Not schema aware: fields are treated generically, so unknown numeric field IDs remain opaque.
- Only length-delimited string/bytes fields that look like UTF-8 are shown; binary blobs stay hidden.
- Recursion depth is capped at 5 and embedded messages larger than 1 MB are skipped for safety.
- Base64 detection expects the entire payload to be base64; mixed encodings are not decoded.

## Development
- Source lives under `src/main/java/x/mux0x/protobufws/`.
- Gradle handles dependencies (Montoya API and protobuf libraries). `./gradlew spotlessApply` or similar
  formatting steps are not enforced yet, so stick to standard Java style.
- Feel free to open issues or PRs with sample protobuf schemas, decoding improvements, or bug fixes.
