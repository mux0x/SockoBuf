package x.mux0x.protobufws;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class ProtobufWsExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Sockobof");
        api.userInterface().registerWebSocketMessageEditorProvider(new ProtobufWsEditorProvider(api));
        api.logging().logToOutput("[Sockobof] loaded.");
    }
}
