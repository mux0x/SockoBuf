package x.mux0x.protobufws;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
// import burp.api.montoya.core.ToolType;
import burp.api.montoya.ui.editor.extension.EditorMode;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static burp.api.montoya.core.ByteArray.byteArray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
class ProtobufWsEditor implements ExtensionProvidedWebSocketMessageEditor {

    private final JTextArea textArea;
    private final JScrollPane scroll;
    private final MontoyaApi api;
    private final boolean isRepeater;
    private final boolean isProxy;
    private List<Integer> utf8LeafIndexes;

    private ProtoWire.Node root;
    private List<ProtoWire.NodePath> editableLeaves;
    private boolean modified = false;
    private boolean payloadWasBase64 = false;

    ProtobufWsEditor(MontoyaApi api, EditorMode editorMode) {
        this.api = api;
        this.isProxy    = (editorMode == EditorMode.READ_ONLY);
        this.isRepeater = !this.isProxy;
        this.textArea = new JTextArea();
        this.textArea.setLineWrap(true);
        this.textArea.setWrapStyleWord(true);
        this.scroll = new JScrollPane(textArea);
        api.userInterface().applyThemeToComponent(scroll);

        this.textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { modified = true; }
            public void removeUpdate(DocumentEvent e) { modified = true; }
            public void changedUpdate(DocumentEvent e) { modified = true; }
        });
        this.textArea.setEditable(this.isRepeater);
    }

    @Override public String caption() { return "Protobuf"; }
    @Override public Component uiComponent() { return this.scroll; }

    @Override
    public boolean isEnabledFor(WebSocketMessage message) {
        try {
            byte[] raw = message.payload().getBytes();
            if (ProtoWire.looksLikeProtobuf(raw)) return true;

            String asText = message.payload().toString();
            byte[] b64 = ProtoWire.tryDecodeBase64(asText);
            return b64 != null && ProtoWire.looksLikeProtobuf(b64);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setMessage(WebSocketMessage message) {
        try {
            modified = false;
            editableLeaves = new ArrayList<>();
            utf8LeafIndexes = new ArrayList<>();
            payloadWasBase64 = false;

            byte[] candidate = message.payload().getBytes();
            if (!ProtoWire.looksLikeProtobuf(candidate)) {
                String asText = message.payload().toString();
                byte[] b64 = ProtoWire.tryDecodeBase64(asText);
                if (b64 != null && ProtoWire.looksLikeProtobuf(b64)) {
                    candidate = b64;
                    payloadWasBase64 = true;
                }
            }

            this.root = ProtoWire.parse(candidate).root;
            this.editableLeaves = ProtoWire.collectEditableLeaves(root);

            // Collect ONLY printable UTF-8 leaves (no base64 shown)
            List<String> values = new ArrayList<>();
            for (int i = 0; i < editableLeaves.size(); i++) {
                ProtoWire.NodePath np = editableLeaves.get(i);
                if (np.utf8) {
                    values.add(new String(np.node.lengthDelimited, StandardCharsets.UTF_8));
                    utf8LeafIndexes.add(i); // index into editableLeaves
                }
            }

            String view;
            if (values.isEmpty()) {
                view = "(No printable UTF-8 string fields found in this protobuf payload.)";
            } else if (values.size() == 1) {
                view = values.get(0);
                // Pretty-print JSON ONLY in Proxy
                if (isProxy) {
                    String pretty = tryPrettyJson(view);
                    if (pretty != null) view = pretty;
                }
            } else {
                // Join by newline for multi-string payloads
                view = String.join("\n", values);
                // For multiple strings, we don't pretty-print (ambiguous which one is JSON).
            }

            textArea.setText(view);
            textArea.setCaretPosition(0);
            textArea.setEditable(isRepeater); // enforce again in case tool changed
            modified = false;
        } catch (Exception e) {
            textArea.setText("Failed to parse as protobuf: " + e.getMessage());
            textArea.setEditable(false);
            modified = false;
        }
    }

    @Override
    public ByteArray getMessage() {
        if (root == null) return byteArray(new byte[0]);

        // Proxy is read-only -> never modify outbound bytes
        if (!isRepeater) {
            byte[] unchanged = ProtoWire.serialize(root);
            return payloadWasBase64
                    ? byteArray(java.util.Base64.getEncoder().encodeToString(unchanged))
                    : byteArray(unchanged);
        }

        if (!modified || editableLeaves == null || editableLeaves.isEmpty()) {
            byte[] unchanged = ProtoWire.serialize(root);
            return payloadWasBase64
                    ? byteArray(java.util.Base64.getEncoder().encodeToString(unchanged))
                    : byteArray(unchanged);
        }

        try {
            // Repeater path: user edited raw decoded text. We only map back to the UTF-8 leaves we showed.
            String body = textArea.getText();
            List<String> parts;
            if (utf8LeafIndexes.size() <= 1) {
                parts = List.of(body);
            } else {
                // Split lines back to individual string fields
                String[] lines = body.split("\\R", -1);
                parts = new ArrayList<>();
                for (String l : lines) parts.add(l);
                // If user added more lines than tracked leaves, merge extras into the last field
                if (parts.size() > utf8LeafIndexes.size()) {
                    List<String> merged = new ArrayList<>();
                    for (int i = 0; i < utf8LeafIndexes.size(); i++) {
                        if (i < utf8LeafIndexes.size() - 1) {
                            merged.add(i < parts.size() ? parts.get(i) : "");
                        } else {
                            // last one gets the rest joined by \n
                            StringBuilder sb = new StringBuilder();
                            for (int j = i; j < parts.size(); j++) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(parts.get(j));
                            }
                            merged.add(sb.toString());
                        }
                    }
                    parts = merged;
                } else if (parts.size() < utf8LeafIndexes.size()) {
                    // pad missing lines with empty strings
                    List<String> padded = new ArrayList<>(parts);
                    while (padded.size() < utf8LeafIndexes.size()) padded.add("");
                    parts = padded;
                }
            }

            // Apply edited parts back to the corresponding leaves
            for (int k = 0; k < utf8LeafIndexes.size(); k++) {
                int leafIdx = utf8LeafIndexes.get(k);
                ProtoWire.Node target = editableLeaves.get(leafIdx).node;
                String content = parts.get(k);
                target.lengthDelimited = content.getBytes(StandardCharsets.UTF_8);
            }

            byte[] rebuilt = ProtoWire.serialize(root);
            return payloadWasBase64
                    ? byteArray(java.util.Base64.getEncoder().encodeToString(rebuilt))
                    : byteArray(rebuilt);
        } catch (Exception e) {
            api.logging().logToError("[WS Protobuf Tab] Re-encode failed: " + e.getMessage());
            byte[] fallback = ProtoWire.serialize(root);
            return payloadWasBase64
                    ? byteArray(java.util.Base64.getEncoder().encodeToString(fallback))
                    : byteArray(fallback);
        }
    }

    @Override public boolean isModified() { return isRepeater && modified; }
    @Override public Selection selectedData() { return null; }

    // ---------- helpers ----------
    private static String tryPrettyJson(String text) {
        try {
            JsonElement el = JsonParser.parseString(text.trim());
            // Only pretty-print objects/arrays
            if (el.isJsonObject() || el.isJsonArray()) {
                Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
                return gson.toJson(el);
            }
        } catch (Exception ignore) {}
        return null;
    }
}
