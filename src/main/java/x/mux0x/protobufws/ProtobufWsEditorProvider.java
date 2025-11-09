package x.mux0x.protobufws;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.editor.extension.WebSocketMessageEditorProvider;
import burp.api.montoya.ui.editor.extension.EditorMode;   // <-- add this

public class ProtobufWsEditorProvider implements WebSocketMessageEditorProvider {
    private final MontoyaApi api;
    public ProtobufWsEditorProvider(MontoyaApi api) { this.api = api; }

    @Override
    public ExtensionProvidedWebSocketMessageEditor provideMessageEditor(EditorCreationContext ctx) {
        EditorMode mode = ctx.editorMode();  // READ_ONLY (Proxy) or DEFAULT (Repeater)
        return new ProtobufWsEditor(api, mode);
    }
}
